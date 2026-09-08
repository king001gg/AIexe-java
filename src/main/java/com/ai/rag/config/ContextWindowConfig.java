package com.ai.rag.config;

import com.ai.rag.util.ContextWindowManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 上下文窗口管理器配置
 */
@Configuration
public class ContextWindowConfig {

    @Value("${agent.max-tokens-per-turn:1500}")
    private int maxTokensPerTurn;

    @Value("${agent.max-history-messages:20}")
    private int maxHistoryMessages;

    @Bean
    public ContextWindowManager contextWindowManager() {
        return new ContextWindowManager(maxTokensPerTurn, maxHistoryMessages);
    }
}