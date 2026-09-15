package com.ai.rag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * 未认证请求的 401 响应（Stage 4）
 *
 * <p>默认实现会返回一个空的 401 或重定向到登录页；这里改为输出与业务错误一致的
 * {@link com.ai.rag.model.dto.ApiError} JSON 体。
 */
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiKeyProperties properties;
    private final ObjectMapper objectMapper;

    public JsonAuthenticationEntryPoint(ApiKeyProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ErrorResponseWriter.write(response, objectMapper, HttpStatus.UNAUTHORIZED,
                "未认证",
                "缺少或无效的 " + properties.getHeader() + " 请求头",
                request);
    }
}
