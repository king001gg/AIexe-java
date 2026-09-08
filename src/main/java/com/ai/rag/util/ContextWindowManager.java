package com.ai.rag.util;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 上下文窗口管理器
 * 基于Guava Cache实现滑动窗口策略，控制Token消耗
 */
@Slf4j
public class ContextWindowManager {

    private final Cache<String, ConversationContext> contextCache;
    private final int maxTokensPerTurn;
    private final int maxHistoryMessages;

    public ContextWindowManager(int maxTokensPerTurn, int maxHistoryMessages) {
        this.maxTokensPerTurn = maxTokensPerTurn;
        this.maxHistoryMessages = maxHistoryMessages;

        this.contextCache = CacheBuilder.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .build();
    }

    /**
     * 添加消息到上下文窗口
     */
    public void addMessage(String sessionId, String role, String content) {
        ConversationContext context = contextCache.getIfPresent(sessionId);
        if (context == null) {
            context = new ConversationContext(sessionId);
            contextCache.put(sessionId, context);
        }

        context.addMessage(role, content);
        ensureWindowLimits(context);
    }

    /**
     * 获取上下文窗口
     */
    public List<ConversationContext.Message> getContext(String sessionId) {
        ConversationContext context = contextCache.getIfPresent(sessionId);
        return context != null ? context.getMessages() : List.of();
    }

    /**
     * 清理上下文窗口
     */
    public void clearContext(String sessionId) {
        contextCache.invalidate(sessionId);
    }

    /**
     * 确保窗口限制
     */
    private void ensureWindowLimits(ConversationContext context) {
        // 按消息数量限制
        if (context.getMessages().size() > maxHistoryMessages) {
            int removeCount = context.getMessages().size() - maxHistoryMessages;
            context.removeFirstMessages(removeCount);
        }

        // 按Token数量限制
        while (context.getTotalTokens() > maxTokensPerTurn && !context.getMessages().isEmpty()) {
            context.removeFirstMessage();
        }
    }

    /**
     * 获取当前Token使用情况
     */
    public TokenUsage getTokenUsage(String sessionId) {
        ConversationContext context = contextCache.getIfPresent(sessionId);
        if (context == null) {
            return new TokenUsage(0, 0, 0);
        }

        int inputTokens = context.getTotalTokens();
        int outputTokens = Math.min(maxTokensPerTurn - inputTokens, maxTokensPerTurn / 2);
        int totalTokens = inputTokens + outputTokens;

        return new TokenUsage(inputTokens, outputTokens, totalTokens);
    }

    /**
     * 获取缓存统计信息
     */
    public CacheStats getCacheStats() {
        return new CacheStats(
                contextCache.size(),
                contextCache.stats().hitCount(),
                contextCache.stats().missCount(),
                contextCache.stats().evictionCount()
        );
    }

    /**
     * 上下文窗口内部类
     */
    public static class ConversationContext {
        private final String sessionId;
        private final List<Message> messages;

        public ConversationContext(String sessionId) {
            this.sessionId = sessionId;
            this.messages = new java.util.ArrayList<>();
        }

        public void addMessage(String role, String content) {
            messages.add(new Message(role, content));
        }

        public void removeFirstMessage() {
            if (!messages.isEmpty()) {
                messages.remove(0);
            }
        }

        public void removeFirstMessages(int count) {
            if (count <= 0) return;
            int removeCount = Math.min(count, messages.size());
            messages.subList(0, removeCount).clear();
        }

        public List<Message> getMessages() {
            return new java.util.ArrayList<>(messages);
        }

        public int getTotalTokens() {
            return messages.stream()
                    .mapToInt(msg -> TokenCounter.estimateTokens(msg.content))
                    .sum();
        }

        /**
         * 消息内部类
         */
        public static class Message {
            public final String role;
            public final String content;

            public Message(String role, String content) {
                this.role = role;
                this.content = content;
            }
        }
    }

    /**
     * Token使用情况
     */
    public static class TokenUsage {
        public final int inputTokens;
        public final int outputTokens;
        public final int totalTokens;

        public TokenUsage(int inputTokens, int outputTokens, int totalTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.totalTokens = totalTokens;
        }
    }

    /**
     * 缓存统计信息
     */
    public static class CacheStats {
        public final long size;
        public final long hitCount;
        public final long missCount;
        public final long evictionCount;

        public CacheStats(long size, long hitCount, long missCount, long evictionCount) {
            this.size = size;
            this.hitCount = hitCount;
            this.missCount = missCount;
            this.evictionCount = evictionCount;
        }
    }
}