package com.ai.rag.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 令牌桶限流器测试
 *
 * <p>通过注入可控时钟，确定性地验证补充速率与突发容量，不依赖真实时间流逝。
 */
class TokenBucketTest {

    private static final double CAPACITY = 3;
    private static final double REFILL_PER_SECOND = 1.0;

    /** 可控时钟（纳秒） */
    private long nowNanos = 0L;

    private TokenBucket bucket;

    @BeforeEach
    void setUp() {
        bucket = new TokenBucket(CAPACITY, REFILL_PER_SECOND, () -> nowNanos);
    }

    private void advance(long duration, TimeUnit unit) {
        nowNanos += unit.toNanos(duration);
    }

    @Test
    @DisplayName("初始为满桶：可连续取走 capacity 个令牌，第 capacity+1 个被拒")
    void startsFullAndAllowsBurst() {
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("按补充速率放行：取空后等 1 秒可再取 1 个")
    void refillsAtConfiguredRate() {
        drain();

        // 未到补充时间：仍被拒
        advance(999, TimeUnit.MILLISECONDS);
        assertThat(bucket.tryAcquire()).isFalse();

        // 满 1 秒：补上 1 个令牌
        advance(1, TimeUnit.MILLISECONDS);
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("补充量不超过桶容量：长时间空闲后仍只能突发 capacity 个")
    void refillIsCappedAtCapacity() {
        drain();
        advance(1, TimeUnit.HOURS);

        int allowed = 0;
        while (bucket.tryAcquire()) {
            allowed++;
        }
        assertThat(allowed).isEqualTo((int) CAPACITY);
    }

    @Test
    @DisplayName("时钟未推进时不补充令牌")
    void doesNotRefillWhenClockStandsStill() {
        drain();
        assertThat(bucket.tryAcquire()).isFalse();
        assertThat(bucket.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("Retry-After：按缺口与速率向上取整，最小 1 秒")
    void reportsSecondsUntilNextToken() {
        assertThat(bucket.secondsUntilNextToken()).isZero();

        drain();
        assertThat(bucket.secondsUntilNextToken()).isEqualTo(1);

        // 半秒后缺口 0.5 个令牌，仍需 1 秒（向上取整）
        advance(500, TimeUnit.MILLISECONDS);
        assertThat(bucket.secondsUntilNextToken()).isEqualTo(1);
    }

    @Test
    @DisplayName("Retry-After：低速补充时反映真实等待秒数")
    void reportsLongerWaitForSlowRefill() {
        // 0.5 个/秒 -> 补满 1 个令牌需 2 秒
        TokenBucket slow = new TokenBucket(1, 0.5, () -> nowNanos);
        assertThat(slow.tryAcquire()).isTrue();
        assertThat(slow.secondsUntilNextToken()).isEqualTo(2);
    }

    @Test
    @DisplayName("容量或速率非法时构造失败")
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new TokenBucket(0, 1.0, () -> nowNanos))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
        assertThatThrownBy(() -> new TokenBucket(1, 0, () -> nowNanos))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refillPerSecond");
    }

    private void drain() {
        while (bucket.tryAcquire()) {
            // 取空为止
        }
    }
}
