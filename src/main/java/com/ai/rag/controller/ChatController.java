package com.ai.rag.controller;

import com.ai.rag.model.dto.ChatRequest;
import com.ai.rag.model.dto.ChatResponse;
import com.ai.rag.service.AgentService;
import com.ai.rag.service.TokenService;
import com.ai.rag.util.ContextWindowManager;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
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
    private final TokenService tokenService;
    private final ContextWindowManager contextWindowManager;

    /**
     * 发送消息
     */
    @PostMapping("/message")
    public ResponseEntity<ChatResponse> sendMessage(@Valid @RequestBody ChatRequest request) {
        log.info("Received chat request from session: {}", request.getSessionId());

        try {
            // 如果没有sessionId，生成一个新的
            if (request.getSessionId() == null || request.getSessionId().isEmpty()) {
                request.setSessionId(UUID.randomUUID().toString());
            }

            // 设置默认工具
            if (request.getEnabledTools() == null || request.getEnabledTools().isEmpty()) {
                request.setEnabledTools(getDefaultTools());
            }

            if (request.getUseTools() == null) {
                request.setUseTools(true);
            }

            // 处理请求
            ChatResponse response = agentService.processRequest(request);

            // 记录Token使用
            if (response.getInputTokens() != null && response.getOutputTokens() != null) {
                tokenService.recordTokenUsage(
                    request.getSessionId(),
                    null,
                    response.getInputTokens(),
                    response.getOutputTokens(),
                    "gpt-4"
                );
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error processing chat request", e);
            return ResponseEntity.internalServerError().build();
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
        List<ContextWindowManager.ConversationContext.Message> context =
            contextWindowManager.getContext(sessionId);

        Map<String, Object> response = new HashMap<>();
        response.put("sessionId", sessionId);
        response.put("messageCount", context.size());
        response.put("messages", context);

        return ResponseEntity.ok(response);
    }

    /**
     * 清除会话上下文
     */
    @DeleteMapping("/context/{sessionId}")
    public ResponseEntity<Map<String, Object>> clearContext(@PathVariable String sessionId) {
        contextWindowManager.clearContext(sessionId);

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
        ContextWindowManager.TokenUsage tokenUsage = contextWindowManager.getTokenUsage(sessionId);

        Map<String, Object> response = new HashMap<>();
        response.put("sessionId", sessionId);
        response.put("inputTokens", tokenUsage.inputTokens);
        response.put("outputTokens", tokenUsage.outputTokens);
        response.put("totalTokens", tokenUsage.totalTokens);

        return ResponseEntity.ok(response);
    }

    /**
     * 获取缓存统计信息
     */
    @GetMapping("/cache/stats")
    public ResponseEntity<ContextWindowManager.CacheStats> getCacheStats() {
        return ResponseEntity.ok(contextWindowManager.getCacheStats());
    }
}