package com.ai.rag.controller;

import com.ai.rag.model.dto.ChatRequest;
import com.ai.rag.model.dto.ChatResponse;
import com.ai.rag.service.AgentService;
import com.ai.rag.service.StreamingChatService;
import com.ai.rag.service.TokenService;
import com.ai.rag.util.TokenCounter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天控制器
 */
@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final AgentService agentService;
    private final StreamingChatService streamingChatService;
    private final TokenService tokenService;
    private final ChatMemoryStore chatMemoryStore;

    /**
     * 发送消息
     */
    @PostMapping("/message")
    public ResponseEntity<ChatResponse> sendMessage(@Valid @RequestBody ChatRequest request) {
        log.info("Received chat request from session: {}", request.getSessionId());

        normalize(request);

        // 处理请求（异常交由全局处理器统一返回错误体）
        ChatResponse response = agentService.processRequest(request);

        // 记录Token使用（传入真实 conversationId）
        if (response.getInputTokens() != null && response.getOutputTokens() != null) {
            tokenService.recordTokenUsage(
                request.getSessionId(),
                parseConversationId(response.getConversationId()),
                response.getInputTokens(),
                response.getOutputTokens(),
                "gpt-4"
            );
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 解析会话ID（响应中的 conversationId 为字符串，token 记录需要 Long）
     */
    private Long parseConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(conversationId);
        } catch (NumberFormatException e) {
            log.warn("Invalid conversationId: {}", conversationId);
            return null;
        }
    }

    /**
     * 流式发送消息（SSE）
     *
     * <p>事件类型见 {@link StreamingChatService}：token / tool / sources / done / error。
     *
     * <p>此处**不使用 {@code @Valid}**：本端点的响应类型是 {@code text/event-stream}，
     * 若由 {@code GlobalExceptionHandler} 返回 JSON 错误体会产生内容协商冲突，
     * 因此改为手动校验并以 SSE {@code error} 事件在流内返回错误。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(@RequestBody ChatRequest request) {
        log.info("Received streaming chat request from session: {}", request.getSessionId());

        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return errorEmitter("消息内容不能为空");
        }
        if (request.getMessage().length() > 4000) {
            return errorEmitter("消息内容不能超过4000字符");
        }

        normalize(request);
        return streamingChatService.stream(request);
    }

    /**
     * 构建一个只推送 error 事件后立即结束的 SSE 流（用于入参校验失败）
     */
    private SseEmitter errorEmitter(String message) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("message", message)));
        } catch (IOException e) {
            log.debug("Failed to send validation error event: {}", e.getMessage());
        }
        emitter.complete();
        return emitter;
    }

    /**
     * 请求默认值填充（sessionId / 工具开关）
     */
    private void normalize(ChatRequest request) {
        if (request.getSessionId() == null || request.getSessionId().isEmpty()) {
            request.setSessionId(UUID.randomUUID().toString());
        }
        if (request.getEnabledTools() == null || request.getEnabledTools().isEmpty()) {
            request.setEnabledTools(getDefaultTools());
        }
        if (request.getUseTools() == null) {
            request.setUseTools(true);
        }
    }

    /**
     * 获取默认工具列表
     */
    private List<String> getDefaultTools() {
        return Arrays.asList("calculator", "weather", "math", "search", "datetime");
    }

    /**
     * 获取会话上下文
     */
    @GetMapping("/context/{sessionId}")
    public ResponseEntity<Map<String, Object>> getContext(@PathVariable String sessionId) {
        List<Map<String, Object>> history = chatMemoryStore.getMessages(sessionId).stream()
            .map(ChatController::toHistoryEntry)
            .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("sessionId", sessionId);
        response.put("messageCount", history.size());
        response.put("messages", history);

        return ResponseEntity.ok(response);
    }

    /**
     * 把一条 {@link ChatMessage} 转成上下文响应里的一项
     *
     * <p><b>为什么不能直接用 {@code Map.of(...)}（缺陷 D11）：</b>
     * {@code Map.of} 的键值都**不允许为 null**，而 {@code AiMessage.text()}
     * 在「只发起工具调用、没有文本内容」时正好返回 null。
     * 于是只要一个会话触发过一次工具调用，再查它的上下文就会 NPE → 500。
     *
     * <p>这里做两件事：把 {@code content} 的 null 兜成空串，
     * 并把工具调用单独透出到 {@code toolCalls} 字段 —— 否则那段「模型决定调什么工具」
     * 的过程在上下文里会变成一条内容为空的、看不出发生过什么的记录，排错时很误导。
     */
    private static Map<String, Object> toHistoryEntry(ChatMessage message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", message.type().name());
        entry.put("content", message.text() == null ? "" : message.text());

        if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
            entry.put("toolCalls", ai.toolExecutionRequests().stream()
                    .map(req -> {
                        Map<String, Object> call = new LinkedHashMap<>();
                        call.put("name", req.name());
                        call.put("arguments", req.arguments());
                        return call;
                    })
                    .toList());
        }
        return entry;
    }

    /**
     * 清除会话上下文
     */
    @DeleteMapping("/context/{sessionId}")
    public ResponseEntity<Map<String, Object>> clearContext(@PathVariable String sessionId) {
        chatMemoryStore.deleteMessages(sessionId);

        Map<String, Object> response = new HashMap<>();
        response.put("sessionId", sessionId);
        response.put("cleared", true);
        response.put("message", "Context cleared successfully");

        return ResponseEntity.ok(response);
    }

    /**
     * 获取会话Token使用情况
     */
    @GetMapping("/tokens/{sessionId}")
    public ResponseEntity<Map<String, Object>> getTokenUsage(@PathVariable String sessionId) {
        int inputTokens = 0;
        int outputTokens = 0;
        for (ChatMessage msg : chatMemoryStore.getMessages(sessionId)) {
            // text() 可能为 null（AiMessage 只发起工具调用时，见 getContext 的注释）；
            // 这类消息没有文本可估，按 0 计。
            int tokens = msg.text() == null ? 0 : TokenCounter.estimateTokens(msg.text());
            if (msg.type() == ChatMessageType.USER) {
                inputTokens += tokens;
            } else {
                outputTokens += tokens;
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("sessionId", sessionId);
        response.put("inputTokens", inputTokens);
        response.put("outputTokens", outputTokens);
        response.put("totalTokens", inputTokens + outputTokens);

        return ResponseEntity.ok(response);
    }
}
