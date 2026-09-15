package com.ai.rag.util;

import java.util.function.LongSupplier;

/**
 * 令牌桶限流器（Stage 4）
 *
 * <p>以固定速率补充令牌，桶内容量上限为 {@code capacity}，因此允许一定程度的突发流量：
 * 空闲一段时间后桶会积攒到满，可瞬时放行 {@code capacity} 个请求；持续超速时
 * 则被平滑限制在 {@code refillPerSecond} 个/秒。
 *
 * <p>未使用 Guava {@code RateLimiter}：其突发容量固定为「1 秒的令牌量」，无法通过配置
 * 表达独立的 burst 语义；此处自行实现以便与 {@code rate-limit.burst} 配置精确对应。
 *
 * <p>线程安全（所有状态访问均在 {@code synchronized} 内）。时钟通过 {@link LongSupplier}
 * 注入，便于单元测试确定性推进时间。
 */
public final class TokenBucket {

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final double capacity;
    private final double refillPerSecond;
    private final LongSupplier clock;

    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, System::nanoTime);
    }

    TokenBucket(double capacity, double refillPerSecond, LongSupplier clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须大于 0，实际：" + capacity);
        }
        if (refillPerSecond <= 0) {
            throw new IllegalArgumentException("refillPerSecond 必须大于 0，实际：" + refillPerSecond);
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.clock = clock;
        this.tokens = capacity;
        this.lastRefillNanos = clock.getAsLong();
    }

    /**
     * 尝试取得一个令牌
     *
     * @return 取到返回 true；桶内不足一个令牌返回 false（调用方应立即拒绝请求，不排队等待）
     */
    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /**
     * 距离补满一个令牌还需多少秒（向上取整，最小 1），用于 {@code Retry-After} 响应头
     */
    public synchronized long secondsUntilNextToken() {
        refill();
        if (tokens >= 1.0) {
            return 0;
        }
        return Math.max((long) Math.ceil((1.0 - tokens) / refillPerSecond), 1);
    }

    /**
     * 按流逝时间补充令牌（上限为桶容量）
     */
    private void refill() {
        long now = clock.getAsLong();
        long elapsed = now - lastRefillNanos;
        if (elapsed <= 0) {
            // 时钟未推进或发生回拨：不补充，也不推进基准时间
            return;
        }
        tokens = Math.min(capacity, tokens + (elapsed / NANOS_PER_SECOND) * refillPerSecond);
        lastRefillNanos = now;
    }
}
