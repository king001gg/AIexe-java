package com.ai.rag.service;

import com.ai.rag.model.entity.Memory;
import com.ai.rag.repository.MemoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 缓存管理服务
 * Redis 不可用时自动降级，不影响核心数据库操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final MemoryRepository memoryRepository;

    private static final String MEMORY_KEY_PREFIX = "memory:";
    private static final String CONVERSATION_KEY_PREFIX = "conversation:";
    private static final String KNOWLEDGE_KEY_PREFIX = "knowledge:";

    /**
     * 获取记忆缓存
     */
    public Optional<String> getMemoryFromCache(String sessionId, String key) {
        try {
            String cacheKey = buildMemoryKey(sessionId, key);
            Object value = redisTemplate.opsForValue().get(cacheKey);
            return value != null ? Optional.of(value.toString()) : Optional.empty();
        } catch (Exception e) {
            log.warn("Redis unavailable, skip memory cache read: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 设置记忆缓存
     */
    public void setMemoryToCache(String sessionId, String key, String value) {
        try {
            String cacheKey = buildMemoryKey(sessionId, key);
            redisTemplate.opsForValue().set(cacheKey, value, 30, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Redis unavailable, skip memory cache write: {}", e.getMessage());
        }
    }

    /**
     * 删除记忆缓存
     */
    public void deleteMemoryFromCache(String sessionId, String key) {
        try {
            String cacheKey = buildMemoryKey(sessionId, key);
            redisTemplate.delete(cacheKey);
        } catch (Exception e) {
            log.warn("Redis unavailable, skip memory cache delete: {}", e.getMessage());
        }
    }

    /**
     * 获取会话缓存
     */
    public Optional<String> getConversationFromCache(String sessionId) {
        try {
            String cacheKey = CONVERSATION_KEY_PREFIX + sessionId;
            Object value = redisTemplate.opsForValue().get(cacheKey);
            return value != null ? Optional.of(value.toString()) : Optional.empty();
        } catch (Exception e) {
            log.warn("Redis unavailable, skip conversation cache read: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 设置会话缓存
     */
    public void setConversationToCache(String sessionId, String conversation) {
        try {
            String cacheKey = CONVERSATION_KEY_PREFIX + sessionId;
            redisTemplate.opsForValue().set(cacheKey, conversation, 10, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Redis unavailable, skip conversation cache write: {}", e.getMessage());
        }
    }

    /**
     * 获取知识缓存
     */
    public Optional<String> getKnowledgeFromCache(String query) {
        try {
            String cacheKey = KNOWLEDGE_KEY_PREFIX + query.hashCode();
            Object value = redisTemplate.opsForValue().get(cacheKey);
            return value != null ? Optional.of(value.toString()) : Optional.empty();
        } catch (Exception e) {
            log.warn("Redis unavailable, skip knowledge cache read: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 设置知识缓存
     */
    public void setKnowledgeToCache(String query, String knowledge) {
        try {
            String cacheKey = KNOWLEDGE_KEY_PREFIX + query.hashCode();
            redisTemplate.opsForValue().set(cacheKey, knowledge, 5, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Redis unavailable, skip knowledge cache write: {}", e.getMessage());
        }
    }

    /**
     * 批量获取记忆
     */
    public List<Memory> getMemories(String sessionId) {
        log.info("Getting memories for session: {}", sessionId);
        return memoryRepository.findBySessionId(sessionId);
    }

    /**
     * 批量保存记忆
     */
    public void saveMemories(List<Memory> memories) {
        for (Memory memory : memories) {
            memoryRepository.save(memory);
        }
    }

    /**
     * 清除会话所有缓存
     */
    public void clearSessionCache(String sessionId) {
        // 清除记忆缓存
        List<Memory> memories = memoryRepository.findBySessionId(sessionId);
        for (Memory memory : memories) {
            deleteMemoryFromCache(sessionId, memory.getKey());
        }

        // 清除会话缓存
        try {
            redisTemplate.delete(CONVERSATION_KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.warn("Redis unavailable, skip conversation cache clear: {}", e.getMessage());
        }
    }

    /**
     * 构建记忆缓存键
     */
    private String buildMemoryKey(String sessionId, String key) {
        return MEMORY_KEY_PREFIX + sessionId + ":" + key;
    }

    /**
     * 获取缓存统计信息
     */
    public CacheStatistics getCacheStatistics() {
        long memoryCacheCount = countKeysByPrefix(MEMORY_KEY_PREFIX);
        long conversationCacheCount = countKeysByPrefix(CONVERSATION_KEY_PREFIX);
        long knowledgeCacheCount = countKeysByPrefix(KNOWLEDGE_KEY_PREFIX);

        return new CacheStatistics(
            memoryCacheCount,
            conversationCacheCount,
            knowledgeCacheCount
        );
    }

    /**
     * 按前缀统计缓存键数量
     */
    private long countKeysByPrefix(String prefix) {
        try {
            java.util.Set<String> keys = redisTemplate.keys(prefix + "*");
            return keys != null ? keys.size() : 0;
        } catch (Exception e) {
            log.warn("Error counting keys with prefix: {}", prefix, e);
            return 0;
        }
    }

    /**
     * 缓存统计信息
     */
    public static class CacheStatistics {
        private final long memoryCacheCount;
        private final long conversationCacheCount;
        private final long knowledgeCacheCount;

        public CacheStatistics(long memoryCacheCount, long conversationCacheCount, long knowledgeCacheCount) {
            this.memoryCacheCount = memoryCacheCount;
            this.conversationCacheCount = conversationCacheCount;
            this.knowledgeCacheCount = knowledgeCacheCount;
        }

        public long getMemoryCacheCount() { return memoryCacheCount; }
        public long getConversationCacheCount() { return conversationCacheCount; }
        public long getKnowledgeCacheCount() { return knowledgeCacheCount; }
    }
}