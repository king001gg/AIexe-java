package com.ai.rag.config;

import com.ai.rag.agent.tools.AgentTools;
import com.ai.rag.service.Assistant;
import com.ai.rag.service.RagService;
import com.ai.rag.service.retrieval.HybridContentRetriever;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j配置类
 *
 * 配置：
 * - OpenAI 聊天模型与嵌入模型
 * - RAG 检索器（ContentRetriever）
 * - 会话上下文窗口（ChatMemory / ChatMemoryStore）
 * - AiServices 助手（function calling + RAG + 多轮记忆）
 */
@Configuration
public class LangChain4jConfig {

    @Value("${langchain4j.openai.api-key}")
    private String apiKey;

    @Value("${langchain4j.openai.base-url}")
    private String baseUrl;

    @Value("${langchain4j.openai.model}")
    private String model;

    @Value("${langchain4j.openai.temperature:0.7}")
    private double temperature;

    @Value("${langchain4j.openai.max-tokens:2000}")
    private int maxTokens;

    @Value("${langchain4j.embedding.model}")
    private String embeddingModel;

    @Value("${langchain4j.embedding.dimension:1536}")
    private int dimension;

    @Value("${langchain4j.openai.timeout-seconds:10}")
    private long timeoutSeconds;

    @Value("${langchain4j.openai.max-retries:1}")
    private int maxRetries;

    @Value("${agent.max-history-messages:20}")
    private int maxHistoryMessages;

    @Value("${rag.retrieval.final-top-k:5}")
    private int ragMaxResults;

    /**
     * 配置聊天语言模型
     */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(maxRetries)
                .build();
    }

    /**
     * 配置流式聊天语言模型（Stage 3：SSE 逐 token 推送）
     */
    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    /**
     * 配置嵌入模型
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(embeddingModel)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(maxRetries)
                .build();
    }

    /**
     * RAG 检索器：多路召回（向量 + 关键词）+ RRF 融合
     *
     * Stage 2 起由 {@link HybridContentRetriever} 承担，替代原先只走向量单路的
     * {@code EmbeddingStoreContentRetriever}。
     */
    @Bean
    public ContentRetriever contentRetriever(RagService ragService) {
        return new HybridContentRetriever(ragService, ragMaxResults);
    }

    /**
     * 会话记忆存储（Stage 0 内存实现，Stage 1 可替换为 Redis/MySQL 实现）
     */
    @Bean
    public ChatMemoryStore chatMemoryStore() {
        return new InMemoryChatMemoryStore();
    }

    /**
     * 按会话提供独立的上下文窗口（ChatMemory）
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(ChatMemoryStore chatMemoryStore) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .chatMemoryStore(chatMemoryStore)
                .maxMessages(maxHistoryMessages)
                .build();
    }

    /**
     * 装配 AiServices 助手（function calling + RAG + 多轮记忆）
     *
     * 同时装配阻塞与流式模型：{@code chat(...)} 走前者，{@code stream(...)} 走后者，两者共用同一份 ChatMemory。
     */
    @Bean
    public Assistant assistant(ChatLanguageModel chatLanguageModel,
                               StreamingChatLanguageModel streamingChatLanguageModel,
                               ContentRetriever contentRetriever,
                               ChatMemoryProvider chatMemoryProvider,
                               AgentTools agentTools) {
        return AiServices.builder(Assistant.class)
                .chatLanguageModel(chatLanguageModel)
                .streamingChatLanguageModel(streamingChatLanguageModel)
                .contentRetriever(contentRetriever)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(agentTools)
                .build();
    }
}
