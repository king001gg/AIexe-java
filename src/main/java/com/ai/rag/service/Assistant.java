package com.ai.rag.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * AI 助手接口（LangChain4j AiServices）
 *
 * 由 {@code AiServices.builder(...)} 动态生成实现：
 * - {@link MemoryId}：按会话隔离的 {@code ChatMemory}（上下文窗口）
 * - {@link UserMessage}：用户输入
 * - 配置了 {@code ContentRetriever} 后会自动注入检索到的知识库内容（RAG）
 * - 配置了 {@code @Tool} 工具后自动具备 function calling 能力
 *
 * <p>Stage 3：
 * <ul>
 *   <li>{@link #chat} 返回 {@link Result}，携带**真实 token 用量**、**工具调用记录**与**检索来源**，
 *       替代 Stage 0~2 的估算值；</li>
 *   <li>{@link #stream} 返回 {@link TokenStream}，供 SSE 逐 token 推送。</li>
 * </ul>
 * 两者共用同一份 {@code ChatMemory}，因此流式与非流式会话上下文互通。
 */
public interface Assistant {

    /**
     * 非流式对话（返回内容 + token 用量 + 工具调用 + 检索来源）
     */
    Result<String> chat(@MemoryId String sessionId, @UserMessage String message);

    /**
     * 流式对话（逐 token 推送）
     */
    TokenStream stream(@MemoryId String sessionId, @UserMessage String message);
}
