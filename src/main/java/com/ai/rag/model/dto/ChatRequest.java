package com.ai.rag.model.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 聊天请求DTO
 */
@Data
public class ChatRequest {

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 4000, message = "消息内容不能超过4000字符")
    private String message;

    @Size(max = 100, message = "会话ID不能超过100字符")
    private String sessionId;

    private String conversationId;

    @Size(max = 100, message = "用户昵称不能超过100字符")
    private String nickname;

    private String personality;

    private Boolean useTools;

    private List<String> enabledTools;

    private Map<String, Object> context;
}