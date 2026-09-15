package com.ai.rag.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * API Key 认证过滤器（Stage 4）
 *
 * <p>只做「认证」不做「拒绝」：命中有效密钥就把调用方写入 {@code SecurityContext}，
 * 未命中则原样放行。是否拒绝交给 Spring Security 的授权规则
 * （{@code anyRequest().authenticated()} + 免认证白名单）与
 * {@link JsonAuthenticationEntryPoint} 决定。
 *
 * <p>这样白名单路径无需在本过滤器里重复匹配一遍，避免两处路径规则不一致。
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    /** 认证通过后授予的权限，便于后续按角色细分端点 */
    private static final String ROLE_API = "ROLE_API";

    private final ApiKeyProperties properties;

    public ApiKeyAuthFilter(ApiKeyProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String callerName = properties.resolveName(request.getHeader(properties.getHeader()));

        if (callerName != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    callerName, null, List.of(new SimpleGrantedAuthority(ROLE_API)));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
