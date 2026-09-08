package com.ai.rag.model.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 记忆操作请求DTO
 */
@Data
public class MemoryRequest {

    @NotBlank(message = "会话ID不能为空")
    @Size(max = 100, message = "会话ID不能超过100字符")
    private String sessionId;

    @NotBlank(message = "记忆键不能为空")
    @Size(max = 255, message = "记忆键不能超过255字符")
    private String key;

    @NotBlank(message = "记忆值不能为空")
    private String value;
}