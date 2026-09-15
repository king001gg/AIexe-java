package com.ai.rag.model.dto;

import java.time.LocalDateTime;

/**
 * 统一错误响应体（供全局异常处理器返回）
 */
public record ApiError(
        LocalDateTime timestamp,
        int status,
        String message,
        String detail,
        String path
) {
}
