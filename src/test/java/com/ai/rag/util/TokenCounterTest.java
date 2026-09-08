package com.ai.rag.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Token计数器测试类
 */
class TokenCounterTest {

    @Test
    void testEstimateTokens() {
        // 测试空文本
        assertEquals(0, TokenCounter.estimateTokens(null));
        assertEquals(0, TokenCounter.estimateTokens(""));

        // 测试简单文本
        assertTrue(TokenCounter.estimateTokens("Hello world") > 0);

        // 测试长文本
        String longText = "This is a test ".repeat(100);
        assertTrue(TokenCounter.estimateTokens(longText) > 100);
    }

    @Test
    void testCountJsonTokens() {
        String json = "{\"key\": \"value\"}";
        assertTrue(TokenCounter.countJsonTokens(json) > 0);
    }

    @Test
    void testCalculateCost() {
        // 测试GPT-4成本计算
        double cost = TokenCounter.calculateCost(1000, 500, "gpt-4");
        assertTrue(cost > 0);

        // 测试嵌入模型成本
        double embeddingCost = TokenCounter.calculateCost(1000, 0, "text-embedding-ada-002");
        assertTrue(embeddingCost >= 0);
    }

    @Test
    void testFormatTokenCount() {
        // 测试小于1000
        assertEquals("500", TokenCounter.formatTokenCount(500));

        // 测试大于1000
        assertEquals("1.5K", TokenCounter.formatTokenCount(1500));
    }
}