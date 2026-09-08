package com.ai.rag.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 聊天响应DTO
 */
@Data
public class ChatResponse {

    private String conversationId;
    private String sessionId;
    private String response;
    private List<ToolCall> toolCalls;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Double cost;
    private String conversationTitle;
    private LocalDateTime timestamp;

    @Data
    public static class ToolCall {
        private String toolName;
        private String toolInput;
        private String toolOutput;
        private String status;
    }
}