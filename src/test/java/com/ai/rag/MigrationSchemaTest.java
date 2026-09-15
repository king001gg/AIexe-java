package com.ai.rag;

import com.ai.rag.model.entity.Conversation;
import com.ai.rag.model.entity.Document;
import com.ai.rag.model.entity.KnowledgeBase;
import com.ai.rag.model.entity.Memory;
import com.ai.rag.model.entity.Message;
import com.ai.rag.model.entity.TokenUsage;
import com.ai.rag.model.entity.ToolCall;
import com.ai.rag.model.entity.UserPreference;
import com.ai.rag.repository.ConversationRepository;
import com.ai.rag.repository.DocumentRepository;
import com.ai.rag.repository.KnowledgeBaseRepository;
import com.ai.rag.repository.MemoryRepository;
import com.ai.rag.repository.MessageRepository;
import com.ai.rag.repository.TokenUsageRepository;
import com.ai.rag.repository.ToolCallRepository;
import com.ai.rag.repository.UserPreferenceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移脚本 / 实体映射一致性测试（Stage 4）
 *
 * <p>Stage 4 把 {@code ddl-auto} 关掉、改由 Flyway 建表后，Hibernate 不再在启动时校验实体与
 * 表结构是否匹配。本测试用「逐表真实读写一遍」补上这道校验：任一行/列名、类型或外键在
 * 迁移脚本里缺失或不一致，插入都会失败。
 *
 * <p>运行在 local profile 的 H2 上，Flyway 会先执行 {@code db/migration/h2/V1__init.sql}。
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class MigrationSchemaTest {

    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private MemoryRepository memoryRepository;
    @Autowired
    private ToolCallRepository toolCallRepository;
    @Autowired
    private TokenUsageRepository tokenUsageRepository;
    @Autowired
    private UserPreferenceRepository userPreferenceRepository;

    @Test
    @DisplayName("8 张表均可按实体定义写入并读回（校验 Flyway 建表与 JPA 映射一致）")
    void everyTableIsWritableThroughJpa() {
        Conversation conversation = new Conversation();
        conversation.setSessionId("schema-check-session");
        conversation.setUserName("tester");
        conversation.setModel("gpt-4");
        Long conversationId = conversationRepository.saveAndFlush(conversation).getId();
        assertThat(conversationId).isNotNull();

        Message message = new Message();
        message.setConversation(conversation);
        message.setRole(Message.Role.USER);
        message.setContent("你好");
        message.setTokens(2);
        assertThat(messageRepository.saveAndFlush(message).getId()).isNotNull();
        assertThat(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).hasSize(1);

        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setName("schema-check-kb");
        knowledgeBase.setDescription("迁移校验用");
        knowledgeBase.setCreatedBy("tester");
        Long knowledgeBaseId = knowledgeBaseRepository.saveAndFlush(knowledgeBase).getId();
        assertThat(knowledgeBaseId).isNotNull();

        Document document = new Document();
        document.setKnowledgeBase(knowledgeBase);
        document.setTitle("分块标题");
        document.setContent("分块正文内容");
        document.setChunkId("chunk-1");
        document.setChunkIndex(0);
        document.setTokens(6);
        document.setVectorId("vector-1");
        assertThat(documentRepository.saveAndFlush(document).getId()).isNotNull();
        assertThat(documentRepository.findByKnowledgeBaseIdAndChunkId(knowledgeBaseId, "chunk-1")).isNotNull();

        // memories.value 是 H2 保留字，依赖 datasource 的 NON_KEYWORDS=VALUE
        Memory memory = new Memory();
        memory.setSessionId("schema-check-session");
        memory.setKey("nickname");
        memory.setValue("小明");
        assertThat(memoryRepository.saveAndFlush(memory).getId()).isNotNull();
        assertThat(memoryRepository.findBySessionIdAndKey("schema-check-session", "nickname")).isPresent();

        ToolCall toolCall = new ToolCall();
        toolCall.setConversationId(conversationId);
        toolCall.setSessionId("schema-check-session");
        toolCall.setToolName("calculator");
        toolCall.setToolInput("1+1");
        toolCall.setToolOutput("2");
        toolCall.setStatus(ToolCall.Status.SUCCESS);
        assertThat(toolCallRepository.saveAndFlush(toolCall).getId()).isNotNull();

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setSessionId("schema-check-session");
        tokenUsage.setConversationId(conversationId);
        tokenUsage.setInputTokens(10);
        tokenUsage.setOutputTokens(5);
        tokenUsage.setTotalTokens(15);
        tokenUsage.setCost(new BigDecimal("0.000600"));
        tokenUsage.setDate(LocalDate.now());
        assertThat(tokenUsageRepository.saveAndFlush(tokenUsage).getId()).isNotNull();

        // preferences 映射为 JSON 列，是最容易与迁移脚本不一致的字段，单独验证读写
        UserPreference preference = new UserPreference();
        preference.setSessionId("schema-check-session");
        preference.setNickname("小明");
        preference.setAiName("小助手");
        preference.setPersonality(UserPreference.Personality.GENTLE);
        preference.setPreferences(Map.of("tone", "简洁"));
        assertThat(userPreferenceRepository.saveAndFlush(preference).getId()).isNotNull();
        assertThat(userPreferenceRepository.findBySessionId("schema-check-session"))
                .isPresent()
                .get()
                .satisfies(saved -> assertThat(saved.getPreferences()).containsEntry("tone", "简洁"));
    }
}
