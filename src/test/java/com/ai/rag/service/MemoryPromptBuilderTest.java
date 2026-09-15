package com.ai.rag.service;

import com.ai.rag.model.entity.Memory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 长期记忆注入测试
 */
class MemoryPromptBuilderTest {

    private static Memory memory(String key, String value) {
        Memory memory = new Memory();
        memory.setSessionId("s1");
        memory.setKey(key);
        memory.setValue(value);
        return memory;
    }

    @Test
    @DisplayName("无记忆时原样返回用户消息")
    void returnsMessageWhenNoMemories() {
        assertThat(MemoryPromptBuilder.format(List.of(), "你好")).isEqualTo("你好");
        assertThat(MemoryPromptBuilder.format(null, "你好")).isEqualTo("你好");
    }

    @Test
    @DisplayName("有记忆时注入记忆前缀并保留原始提问")
    void injectsMemoriesBeforeQuestion() {
        String prompt = MemoryPromptBuilder.format(
                List.of(memory("nickname", "小明"), memory("tone", "简洁")), "介绍一下 Milvus");

        assertThat(prompt)
                .startsWith("【用户长期记忆】")
                .contains("- nickname: 小明")
                .contains("- tone: 简洁")
                .endsWith("【用户本次提问】\n介绍一下 Milvus");
    }

    @Test
    @DisplayName("记忆条数受 MAX_MEMORIES 限制")
    void capsMemoryCount() {
        List<Memory> memories = new ArrayList<>();
        for (int i = 0; i < MemoryPromptBuilder.MAX_MEMORIES + 5; i++) {
            memories.add(memory("k" + i, "v" + i));
        }

        String prompt = MemoryPromptBuilder.format(memories, "q");

        assertThat(prompt).contains("- k0: v0");
        assertThat(prompt).doesNotContain("- k" + MemoryPromptBuilder.MAX_MEMORIES + ": ");
        assertThat(prompt).doesNotContain("- k" + (MemoryPromptBuilder.MAX_MEMORIES + 4) + ": ");
    }
}
