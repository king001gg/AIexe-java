package com.ai.rag.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j配置类
 *
 * 配置：
 * - OpenAI聊天模型
 * - OpenAI嵌入模型
 * - 模型参数设置
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
}