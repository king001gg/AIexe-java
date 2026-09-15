package com.ai.rag.security;

import com.ai.rag.model.dto.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 过滤器层错误响应写出（Stage 4）
 *
 * <p>安全过滤器运行在 DispatcherServlet 之前，{@code @RestControllerAdvice} 无法介入，
 * 因此这里手工写出与 {@code GlobalExceptionHandler} 一致的 {@link ApiError} JSON 体，
 * 保证客户端只需处理一种错误格式。
 */
final class ErrorResponseWriter {

    private ErrorResponseWriter() {
    }

    static void write(HttpServletResponse response,
                      ObjectMapper objectMapper,
                      HttpStatus status,
                      String message,
                      String detail,
                      HttpServletRequest request) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ApiError body = new ApiError(
                LocalDateTime.now(),
                status.value(),
                message,
                detail,
                request.getRequestURI()
        );
        objectMapper.writeValue(response.getWriter(), body);
    }
}
