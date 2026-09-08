package com.ai.rag.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上下文窗口管理器测试类
 */
class ContextWindowManagerTest {

    private ContextWindowManager contextWindowManager;

    @BeforeEach
    void setUp() {
        contextWindowManager = new ContextWindowManager(1000, 20);
    }

    @Test
    void testAddMessage() {
        contextWindowManager.addMessage("session1", "user", "Hello");
        contextWindowManager.addMessage("session1", "assistant", "Hi there");

        List<ContextWindowManager.ConversationContext.Message> context =
            contextWindowManager.getContext("session1");

        assertNotNull(context);
        assertEquals(2, context.size());
    }

    @Test
    void testGetContextEmpty() {
        List<ContextWindowManager.ConversationContext.Message> context =
            contextWindowManager.getContext("nonexistent");

        assertNotNull(context);
        assertTrue(context.isEmpty());
    }

    @Test
    void testClearContext() {
        contextWindowManager.addMessage("session1", "user", "Hello");
        contextWindowManager.clearContext("session1");

        List<ContextWindowManager.ConversationContext.Message> context =
            contextWindowManager.getContext("session1");

        assertNotNull(context);
        assertTrue(context.isEmpty());
    }

    @Test
    void testWindowLimits() {
        // 创建一个小窗口管理器
        ContextWindowManager smallManager = new ContextWindowManager(100, 5);

        // 添加超过限制的消息
        for (int i = 0; i < 10; i++) {
            smallManager.addMessage("session1", "user", "Message " + i);
        }

        List<ContextWindowManager.ConversationContext.Message> context =
            smallManager.getContext("session1");

        // 不应超过最大消息数
        assertTrue(context.size() <= 5);
    }

    @Test
    void testGetTokenUsage() {
        contextWindowManager.addMessage("session1", "user", "Hello world");
        ContextWindowManager.TokenUsage tokenUsage = contextWindowManager.getTokenUsage("session1");

        assertNotNull(tokenUsage);
        assertTrue(tokenUsage.inputTokens >= 0);
        assertTrue(tokenUsage.totalTokens >= tokenUsage.inputTokens);
    }

    @Test
    void testGetCacheStats() {
        contextWindowManager.addMessage("session1", "user", "Hello");
        ContextWindowManager.CacheStats stats = contextWindowManager.getCacheStats();

        assertNotNull(stats);
        assertTrue(stats.size >= 0);
    }
}