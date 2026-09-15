package com.ai.rag.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * API Key 认证配置（Stage 4）
 *
 * <pre>
 * security:
 *   enabled: true
 *   header: X-API-Key
 *   api-keys:
 *     - name: default
 *       key: ${API_KEY}
 *   permit-all:
 *     - /actuator/health
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "security")
public class ApiKeyProperties {

    /** 是否启用认证；false 时所有请求直接放行（本地开发/单测） */
    private boolean enabled = false;

    /** 携带密钥的请求头名称 */
    private String header = "X-API-Key";

    /** 允许的密钥列表 */
    private List<ApiKey> apiKeys = new ArrayList<>();

    /** 免认证路径（Ant 风格，相对 context-path 之后的路径） */
    private List<String> permitAll = new ArrayList<>();

    /**
     * 单条 API Key
     */
    @Data
    public static class ApiKey {
        /** 调用方名称，用于日志与限流分桶标识 */
        private String name;
        /** 密钥明文（建议通过环境变量注入） */
        private String key;
    }

    /**
     * 校验请求头中的密钥，命中则返回调用方名称，否则返回 {@code null}
     *
     * <p>使用 {@link MessageDigest#isEqual} 做常数时间比较，且**遍历全部候选而不提前返回**，
     * 避免通过响应耗时泄漏「匹配到第几个密钥」。
     */
    public String resolveName(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank() || apiKeys.isEmpty()) {
            return null;
        }
        byte[] presented = presentedKey.getBytes(StandardCharsets.UTF_8);
        String matched = null;
        for (ApiKey candidate : apiKeys) {
            if (candidate == null || candidate.getKey() == null || candidate.getKey().isEmpty()) {
                continue;
            }
            byte[] expected = candidate.getKey().getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(expected, presented)) {
                matched = candidate.getName() == null ? "unnamed" : candidate.getName();
            }
        }
        return matched;
    }
}
