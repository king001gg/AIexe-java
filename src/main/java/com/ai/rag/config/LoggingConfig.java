package com.ai.rag.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

/**
 * 日志和Web配置类
 *
 * 配置：
 * - CORS跨域
 * - 日志拦截器
 * - Web MVC配置
 */
@Configuration
public class LoggingConfig implements WebMvcConfigurer {

    /**
     * 配置CORS跨域
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("http://localhost:3000", "http://localhost:8080", "http://localhost:8501")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 添加拦截器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 这里可以添加自定义的拦截器，如日志拦截器、认证拦截器等
        // registry.addInterceptor(new LoggingInterceptor());
    }
}