package com.ai.rag.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流配置（Stage 4）
 *
 * <pre>
 * rate-limit:
 *   enabled: true
 *   requests-per-minute: 60
 *   burst: 10
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    /** 是否启用限流；false 时所有请求直接放行 */
    private boolean enabled = false;

    /** 每个调用方每分钟允许的请求数（令牌补充速率） */
    private int requestsPerMinute = 60;

    /** 突发容量：桶内最多可积攒的令牌数，决定瞬时允许的额外请求 */
    private int burst = 10;

    /**
     * 令牌补充速率（个/秒）
     */
    public double refillPerSecond() {
        return Math.max(requestsPerMinute, 1) / 60.0;
    }

    /**
     * 桶容量（至少 1，否则永远无法放行）
     */
    public double capacity() {
        return Math.max(burst, 1);
    }
}
