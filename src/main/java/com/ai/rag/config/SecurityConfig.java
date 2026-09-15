package com.ai.rag.config;

import com.ai.rag.security.ApiKeyAuthFilter;
import com.ai.rag.security.ApiKeyProperties;
import com.ai.rag.security.JsonAuthenticationEntryPoint;
import com.ai.rag.security.RateLimitFilter;
import com.ai.rag.security.RateLimitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;

/**
 * 安全配置（Stage 4）
 *
 * <p>API Key 认证 + 令牌桶限流，均为无状态设计：
 * <ul>
 *   <li>{@code security.enabled=false} 时整条链路放行（本地开发/单测）</li>
 *   <li>{@code rate-limit.enabled=false} 时关闭限流</li>
 * </ul>
 *
 * <p>认证与限流的分工：认证过滤器只负责「认出调用方」，拒绝由授权规则 + 401 入口点完成；
 * 限流注册为 Servlet 过滤器（在安全过滤链之前），因此对未认证请求同样生效，能防止
 * 攻击者用无效密钥刷接口。
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({ApiKeyProperties.class, RateLimitProperties.class})
@RequiredArgsConstructor
public class SecurityConfig {

    private final ApiKeyProperties apiKeyProperties;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 无状态 API：不启用 CSRF / 表单登录 / Basic / Session
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!apiKeyProperties.isEnabled()) {
            http.authorizeHttpRequests(registry -> registry.anyRequest().permitAll());
            return http.build();
        }

        http.authorizeHttpRequests(registry -> registry
                        .requestMatchers(permitAllMatchers()).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new JsonAuthenticationEntryPoint(apiKeyProperties, objectMapper)))
                .addFilterBefore(new ApiKeyAuthFilter(apiKeyProperties),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 免认证路径
     *
     * <p>用 {@link AntPathRequestMatcher} 而非字符串重载：字符串重载在 Spring MVC 存在时会
     * 走 {@code MvcRequestMatcher}，而 actuator 端点由独立的 HandlerMapping 处理，
     * 用 MVC 匹配器可能匹配不上。
     */
    private RequestMatcher[] permitAllMatchers() {
        List<String> patterns = apiKeyProperties.getPermitAll();
        return patterns.stream()
                .map(AntPathRequestMatcher::new)
                .toArray(RequestMatcher[]::new);
    }

    /**
     * 限流过滤器注册（先于安全过滤链执行）
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitProperties rateLimitProperties) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                new RateLimitFilter(rateLimitProperties, apiKeyProperties, objectMapper));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("rateLimitFilter");
        // 关闭时保留 Bean 但不注册，便于通过配置快速启停
        registration.setEnabled(rateLimitProperties.isEnabled());
        return registration;
    }
}
