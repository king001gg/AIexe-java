package com.ai.rag.service;

import com.ai.rag.model.dto.ChatRequest;
import com.ai.rag.model.dto.ChatResponse;
import com.ai.rag.model.entity.Conversation;
import com.ai.rag.model.entity.Message;
import com.ai.rag.util.TokenCounter;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent核心服务（非流式）
 *
 * 通过 LangChain4j {@link Assistant}（AiServices）处理请求，
 * 由框架自动完成 RAG 检索、工具调用（function calling）与多轮记忆。
 *
 * <p>Stage 3：改用 {@link Result} 接收返回值，从而拿到**真实 token 用量**与**工具调用记录**，
 * 取代此前的纯估算；模型未返回用量时由 {@link TokenUsageResolver} 自动回退估算。
 * 流式对话见 {@link StreamingChatService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final Assistant assistant;
    private final ConversationService conversationService;
    private final MemoryPromptBuilder memoryPromptBuilder;

    @Value("${langchain4j.openai.model:gpt-4}")
    private String modelName;

    /**
     * 处理用户请求
     */
    public ChatResponse processRequest(ChatRequest request) {
        String sessionId = request.getSessionId();
        log.info("Processing chat request for session: {}", sessionId);

        // 1. 会话落库（幂等获取/创建，避免事务横跨大模型调用）
        Conversation conversation = conversationService.getOrCreateConversation(
                sessionId, request.getNickname(), modelName);
        Long conversationId = conversation.getId();

        // 2. 用户消息落库（保存原始消息，不含注入的记忆）
        conversationService.saveMessage(sessionId, Message.Role.USER, request.getMessage(),
                TokenCounter.estimateTokens(request.getMessage()));

        // 3. 记忆注入：把长期记忆拼进 prompt，让大模型回答时参考
        String prompt = memoryPromptBuilder.build(request.getMessage(), sessionId);

        try {
            Result<String> result = assistant.chat(sessionId, prompt);
            String response = result.content();

            // 4. 真实 token 用量（模型未返回时回退估算）
            TokenUsageResolver.Resolved tokens =
                    TokenUsageResolver.resolve(result.tokenUsage(), prompt, response);

            // 5. 助手消息落库
            conversationService.saveMessage(sessionId, Message.Role.ASSISTANT, response, tokens.outputTokens());

            // 6. 首次对话时用用户消息摘要更新标题
            String title = conversationService.updateTitleIfDefault(sessionId, summarizeTitle(request.getMessage()));

            return buildResponse(response, request, conversationId, title, tokens, result.toolExecutions());

        } catch (Exception e) {
            log.error("Error processing chat request", e);
            throw new RuntimeException("Failed to process request", e);
        }
    }

    /**
     * 用用户消息生成会话标题摘要（截断到 30 字符）
     */
    private String summarizeTitle(String message) {
        String text = message == null ? "" : message.trim();
        return text.length() > 30 ? text.substring(0, 30) : text;
    }

    /**
     * 构建响应
     */
    private ChatResponse buildResponse(String response,
                                       ChatRequest request,
                                       Long conversationId,
                                       String title,
                                       TokenUsageResolver.Resolved tokens,
                                       List<ToolExecution> toolExecutions) {
        ChatResponse chatResponse = new ChatResponse();
        chatResponse.setConversationId(String.valueOf(conversationId));
        chatResponse.setSessionId(request.getSessionId());
        chatResponse.setResponse(response);
        chatResponse.setToolCalls(mapToolCalls(toolExecutions));
        chatResponse.setInputTokens(tokens.inputTokens());
        chatResponse.setOutputTokens(tokens.outputTokens());
        chatResponse.setTotalTokens(tokens.totalTokens());
        chatResponse.setCost(TokenCounter.calculateCost(tokens.inputTokens(), tokens.outputTokens(), modelName));
        chatResponse.setConversationTitle(title);
        chatResponse.setTimestamp(LocalDateTime.now());
        return chatResponse;
    }

    /**
     * 工具执行记录 -> 响应中的 toolCalls
     *
     * 注意：{@link ToolExecution} 不携带失败状态（工具抛异常会中断链路而非返回记录），
     * 因此此处统一标记 SUCCESS；权威的成败与错误信息以 {@code tool_calls} 表为准
     * （由 {@code AgentTools} 落库）。
     */
    private List<ChatResponse.ToolCall> mapToolCalls(List<ToolExecution> toolExecutions) {
        if (toolExecutions == null || toolExecutions.isEmpty()) {
            return List.of();
        }
        return toolExecutions.stream().map(execution -> {
            ChatResponse.ToolCall toolCall = new ChatResponse.ToolCall();
            toolCall.setToolName(execution.request().name());
            toolCall.setToolInput(execution.request().arguments());
            toolCall.setToolOutput(execution.result());
            toolCall.setStatus("SUCCESS");
            return toolCall;
        }).toList();
    }
}
