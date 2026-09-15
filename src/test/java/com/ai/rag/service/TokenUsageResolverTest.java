package com.ai.rag.service;

import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token 用量解析测试
 */
class TokenUsageResolverTest {

    @Test
    @DisplayName("模型返回真实用量时优先采用，且标记为非估算")
    void prefersActualUsage() {
        TokenUsageResolver.Resolved resolved =
                TokenUsageResolver.resolve(new TokenUsage(120, 45, 165), "任意输入", "任意输出");

        assertThat(resolved.inputTokens()).isEqualTo(120);
        assertThat(resolved.outputTokens()).isEqualTo(45);
        assertThat(resolved.totalTokens()).isEqualTo(165);
        assertThat(resolved.estimated()).isFalse();
    }

    @Test
    @DisplayName("模型未返回总用量时由输入+输出推导")
    void derivesTotalWhenMissing() {
        TokenUsageResolver.Resolved resolved =
                TokenUsageResolver.resolve(new TokenUsage(10, 20, null), "in", "out");

        assertThat(resolved.totalTokens()).isEqualTo(30);
        assertThat(resolved.estimated()).isFalse();
    }

    @Test
    @DisplayName("用量为 null 时回退估算并标记 estimated")
    void fallsBackWhenUsageIsNull() {
        TokenUsageResolver.Resolved resolved =
                TokenUsageResolver.resolve(null, "hello world", "hi there");

        assertThat(resolved.estimated()).isTrue();
        assertThat(resolved.inputTokens()).isEqualTo(2);
        assertThat(resolved.outputTokens()).isEqualTo(2);
        assertThat(resolved.totalTokens()).isEqualTo(4);
    }

    @Test
    @DisplayName("用量对象存在但字段为 null 时同样回退估算")
    void fallsBackWhenCountsAreNull() {
        TokenUsageResolver.Resolved resolved =
                TokenUsageResolver.resolve(new TokenUsage(null, null, null), "a b c", "d");

        assertThat(resolved.estimated()).isTrue();
        assertThat(resolved.inputTokens()).isEqualTo(3);
        assertThat(resolved.outputTokens()).isEqualTo(1);
    }
}
