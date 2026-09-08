package com.ai.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * AI RAG Agent 应用启动类
 *
 * 基于LangChain4j构建的RAG智能问答Agent，支持：
 * - 多格式文档解析（PDF/Word/Excel）
 * - 向量化存储与检索
 * - ReAct模式Agent循环
 * - 工具调用集成
 * - 上下文窗口管理
 */
@SpringBootApplication
@EnableCaching
@EnableAsync
public class AiRagAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiRagAgentApplication.class, args);
    }
}