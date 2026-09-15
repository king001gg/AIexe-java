package com.ai.rag.service;

import com.ai.rag.model.entity.Memory;
import com.ai.rag.repository.MemoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 长期记忆注入器（Stage 1 引入，Stage 3 抽为独立组件供流式/非流式共用）
 *
 * <p>把会话的长期记忆格式化为 prompt 前缀，让大模型回答时参考用户偏好等持久化信息。
 */
@Component
@RequiredArgsConstructor
public class MemoryPromptBuilder {

    /** 单次最多注入的记忆条数 */
    static final int MAX_MEMORIES = 10;

    private final MemoryRepository memoryRepository;

    /**
     * 读取会话记忆并构建带记忆前缀的 prompt
     */
    public String build(String userMessage, String sessionId) {
        return format(memoryRepository.findBySessionIdOrderByUpdatedAtDesc(sessionId), userMessage);
    }

    /**
     * 纯函数格式化（无记忆时原样返回用户消息）
     */
    public static String format(List<Memory> memories, String userMessage) {
        if (memories == null || memories.isEmpty()) {
            return userMessage;
        }

        String memoryBlock = memories.stream()
                .limit(MAX_MEMORIES)
                .map(m -> "- " + m.getKey() + ": " + m.getValue())
                .collect(Collectors.joining("\n"));

        return "【用户长期记忆】\n" + memoryBlock + "\n\n【用户本次提问】\n" + userMessage;
    }
}
