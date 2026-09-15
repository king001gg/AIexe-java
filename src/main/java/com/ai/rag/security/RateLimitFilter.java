package com.ai.rag.security;

import com.ai.rag.util.TokenBucket;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 限流过滤器（Stage 4）
 *
 * <p>按调用方维度做令牌桶限流：优先用 API Key 区分调用方，无密钥时退化为按来源 IP。
 * 超限返回 429 + {@code Retry-After}，响应体与业务错误同构。
 *
 * <p>桶存放在带过期时间的 Guava Cache 中，避免为每个调用方永久持有一个桶（内存无上限增长）。
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** 调用方标识（哈希后的 API Key 或 IP 前缀） */
    private static final String BUCKET_KEY_PREFIX_API_KEY = "key:";
    private static final String BUCKET_KEY_PREFIX_IP = "ip:";

    private static final Duration BUCKET_IDLE_EXPIRY = Duration.ofMinutes(10);
    private static final long MAX_TRACKED_CALLERS = 10_000L;

    private final RateLimitProperties properties;
    private final ApiKeyProperties apiKeyProperties;
    private final ObjectMapper objectMapper;
    private final Cache<String, TokenBucket> buckets;

    public RateLimitFilter(RateLimitProperties properties,
                           ApiKeyProperties apiKeyProperties,
                           ObjectMapper objectMapper) {
        this.properties = properties;
        this.apiKeyProperties = apiKeyProperties;
        this.objectMapper = objectMapper;
        this.buckets = CacheBuilder.newBuilder()
                .expireAfterAccess(BUCKET_IDLE_EXPIRY)
                .maximumSize(MAX_TRACKED_CALLERS)
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        TokenBucket bucket = buckets.asMap()
                .computeIfAbsent(resolveBucketKey(request),
                        key -> new TokenBucket(properties.capacity(), properties.refillPerSecond()));

        if (bucket.tryAcquire()) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = bucket.secondsUntilNextToken();
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        ErrorResponseWriter.write(response, objectMapper, HttpStatus.TOO_MANY_REQUESTS,
                "请求过于频繁",
                "已超出限流阈值（" + properties.getRequestsPerMinute() + " 次/分钟），请在 "
                        + retryAfterSeconds + " 秒后重试",
                request);
    }

    /**
     * 解析限流分桶标识
     *
     * <p>密钥只以其 SHA-256 前缀入桶，避免明文密钥作为 Map key 常驻内存。
     *
     * <p>无密钥时退化为 {@code getRemoteAddr()}，**不读取 X-Forwarded-For**：该头由客户端
     * 可控，直接采信会让限流被随意绕过。若部署在反向代理之后需要按真实客户端 IP 限流，
     * 应在代理层设置可信的转发头后再改此处。
     */
    private String resolveBucketKey(HttpServletRequest request) {
        String presentedKey = request.getHeader(apiKeyProperties.getHeader());
        if (presentedKey != null && !presentedKey.isBlank()) {
            return BUCKET_KEY_PREFIX_API_KEY + sha256Prefix(presentedKey);
        }
        return BUCKET_KEY_PREFIX_IP + request.getRemoteAddr();
    }

    /**
     * 取 SHA-256 前 8 字节的十六进制表示（16 个字符），足以区分调用方且不泄漏密钥
     */
    private String sha256Prefix(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            byte[] prefix = new byte[8];
            System.arraycopy(digest, 0, prefix, 0, prefix.length);
            return HexFormat.of().formatHex(prefix);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，正常不会发生
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
