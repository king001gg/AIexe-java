package com.ai.rag.service;

import com.ai.rag.model.dto.ChatRequest;
import com.ai.rag.model.dto.ChatResponse;
import com.ai.rag.model.entity.Conversation;
import com.ai.rag.model.entity.Message;
import com.ai.rag.util.TokenCounter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式对话服务（Stage 3，SSE）
 *
 * <p>通过 {@link TokenStream} 把大模型的输出逐 token 推送给前端，事件类型：
 * <ul>
 *   <li>{@code token}   —— 增量文本 {@code {"content":"..."}}</li>
 *   <li>{@code tool}    —— 工具调用 {@code {"toolName","toolInput","toolOutput"}}</li>
 *   <li>{@code sources} —— 检索来源（RAG 命中分块）{@code [{chunkId,score,source}]}</li>
 *   <li>{@code done}    —— 结束，含完整回答、真实 token 用量、成本与工具调用列表</li>
 *   <li>{@code error}   —— 失败信息（错误以事件形式在流内返回，便于前端统一处理）</li>
 * </ul>
 *
 * <p>会话/消息持久化、记忆注入、真实 token 计量与非流式路径（{@link AgentService}）保持一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamingChatService {

    private final Assistant assistant;
    private final ConversationService conversationService;
    private final MemoryPromptBuilder memoryPromptBuilder;
    private final TokenService tokenService;

    @Value("${agent.stream-timeout-ms:180000}")
    private long streamTimeoutMs;

    @Value("${langchain4j.openai.model:gpt-4}")
    private String modelName;

    /**
     * 发起流式对话
     */
    public SseEmitter stream(ChatRequest request) {
        return stream(request, new SseEmitter(streamTimeoutMs));
    }

    /**
     * 发起流式对话（可注入 emitter，供单元测试捕获 SSE 事件）
     */
    SseEmitter stream(ChatRequest request, SseEmitter emitter) {
        AtomicBoolean closed = new AtomicBoolean(false);

        // 客户端断开 / 超时后不再尝试写入
        emitter.onTimeout(() -> {
            log.warn("SSE stream timeout for session: {}", request.getSessionId());
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(e -> {
            log.warn("SSE stream error for session {}: {}", request.getSessionId(), e.getMessage());
            closed.set(true);
        });
        emitter.onCompletion(() -> closed.set(true));

        String sessionId = request.getSessionId();

        try {
            // 1. 会话与用户消息落库
            Conversation conversation = conversationService.getOrCreateConversation(
                    sessionId, request.getNickname(), modelName);
            Long conversationId = conversation.getId();
            conversationService.saveMessage(sessionId, Message.Role.USER, request.getMessage(),
                    TokenCounter.estimateTokens(request.getMessage()));

            // 2. 记忆注入
            String prompt = memoryPromptBuilder.build(request.getMessage(), sessionId);

            // 3. 逐 token 推送
            StringBuilder answer = new StringBuilder();
            TokenStream tokenStream = assistant.stream(sessionId, prompt);

            tokenStream
                    .onRetrieved(contents -> send(emitter, closed, "sources", toSourcePayloads(contents)))
                    .onToolExecuted(execution -> send(emitter, closed, "tool", toToolPayload(execution)))
                    .onNext(token -> {
                        answer.append(token);
                        send(emitter, closed, "token", Map.of("content", token));
                    })
                    .onComplete(response -> finish(emitter, closed, response, answer, prompt, sessionId, conversationId))
                    .onError(error -> fail(emitter, closed, error))
                    .start();

        } catch (Exception e) {
            // 建流阶段就失败（如会话/记忆落库异常）
            fail(emitter, closed, e);
        }

        return emitter;
    }

    /**
     * 流正常结束：落库助手消息、记录真实 token、更新标题并推送 done 事件
     */
    private void finish(SseEmitter emitter,
                        AtomicBoolean closed,
                        Response<AiMessage> response,
                        StringBuilder answer,
                        String prompt,
                        String sessionId,
                        Long conversationId) {
        try {
            String text = answer.toString();

            // 真实 token 用量（模型未返回时以 prompt/回答做估算回退）
            TokenUsageResolver.Resolved tokens = TokenUsageResolver.resolve(
                    response == null ? null : response.tokenUsage(), prompt, text);

            conversationService.saveMessage(sessionId, Message.Role.ASSISTANT, text, tokens.outputTokens());
            String title = conversationService.updateTitleIfDefault(sessionId, summarizeTitle(text));
            tokenService.recordTokenUsage(sessionId, conversationId,
                    tokens.inputTokens(), tokens.outputTokens(), modelName);

            send(emitter, closed, "done", Map.of(
                    "conversationId", String.valueOf(conversationId),
                    "sessionId", sessionId,
                    "response", text,
                    "conversationTitle", title,
                    "inputTokens", tokens.inputTokens(),
                    "outputTokens", tokens.outputTokens(),
                    "totalTokens", tokens.totalTokens(),
                    "tokensEstimated", tokens.estimated(),
                    "cost", TokenCounter.calculateCost(tokens.inputTokens(), tokens.outputTokens(), modelName)
            ));

            log.info("Streaming completed for session: {}, estimated={}, tokens={}",
                    sessionId, tokens.estimated(), tokens.totalTokens());

        } catch (Exception e) {
            log.error("Error finalizing stream for session: {}", sessionId, e);
            send(emitter, closed, "error", Map.of("message", "流式响应收尾失败：" + e.getMessage()));
        } finally {
            closed.set(true);
            emitter.complete();
        }
    }

    /**
     * 流异常结束
     */
    private void fail(SseEmitter emitter, AtomicBoolean closed, Throwable error) {
        log.error("Streaming failed", error);
        send(emitter, closed, "error", Map.of(
                "message", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        closed.set(true);
        emitter.complete();
    }

    /**
     * 写入一个 SSE 事件（连接已关闭或写入失败时静默跳过）
     */
    private void send(SseEmitter emitter, AtomicBoolean closed, String event, Object data) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            // 客户端断开连接属正常情况（用户关闭页面/取消请求），仅停止后续推送
            log.debug("SSE send skipped for event {}: {}", event, e.getMessage());
            closed.set(true);
        }
    }

    /**
     * 工具执行记录 -> SSE 载荷
     */
    private Map<String, Object> toToolPayload(ToolExecution execution) {
        return Map.of(
                "toolName", execution.request().name(),
                "toolInput", execution.request().arguments(),
                "toolOutput", execution.result() == null ? "" : execution.result());
    }

    /**
     * RAG 检索来源 -> SSE 载荷（供前端展示引用）
     */
    private List<Map<String, Object>> toSourcePayloads(List<Content> contents) {
        if (contents == null) {
            return List.of();
        }
        return contents.stream().map(content -> {
            var segment = content.textSegment();
            var metadata = segment.metadata();
            return Map.<String, Object>of(
                    "chunkId", metadata.getString("chunkId") == null ? "" : metadata.getString("chunkId"),
                    "documentId", metadata.getString("documentId") == null ? "" : metadata.getString("documentId"),
                    "score", metadata.getString("rrfScore") == null ? "" : metadata.getString("rrfScore"),
                    "source", metadata.getString("source") == null ? "" : metadata.getString("source"),
                    "preview", abbreviate(segment.text()));
        }).toList();
    }

    /**
     * 截断预览文本（最多 120 字符）
     */
    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }

    /**
     * 生成会话标题摘要（与 {@link AgentService} 保持一致：截断到 30 字符）
     */
    private String summarizeTitle(String message) {
        String text = message == null ? "" : message.trim();
        return text.length() > 30 ? text.substring(0, 30) : text;
    }
}
