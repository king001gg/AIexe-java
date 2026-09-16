package com.ai.rag.model.dto;

import com.ai.rag.model.entity.Document;

import java.time.LocalDateTime;

/**
 * 文档分块摘要 —— 读接口的响应体
 *
 * <p><b>为什么不直接返回 {@link Document} 实体（缺陷 D8）：</b>
 * 实体上有一条 {@code @ManyToOne(fetch = LAZY) KnowledgeBase knowledgeBase}。
 * 控制器返回实体时，Jackson 会调用 {@code getKnowledgeBase()}，
 * 而此时 Session 已关闭（{@code open-in-view: false}），
 * 抛 {@code LazyInitializationException} → 端点 500。
 *
 * <p>这里只取关联对象的**主键**（{@code knowledgeBaseId}）而不是整个对象，
 * 既满足了调用方想知道「这块属于哪个知识库」的需求，又不会触发懒加载。
 * 对未初始化的 Hibernate 代理调用 {@code getId()} 不会触发 SQL，是安全的。
 */
public record DocumentSummary(
        Long id,
        Long knowledgeBaseId,
        String title,
        String content,
        String chunkId,
        Integer chunkIndex,
        Integer tokens,
        String vectorId,
        LocalDateTime createdAt
) {

    public static DocumentSummary from(Document doc) {
        return new DocumentSummary(
                doc.getId(),
                doc.getKnowledgeBase() == null ? null : doc.getKnowledgeBase().getId(),
                doc.getTitle(),
                doc.getContent(),
                doc.getChunkId(),
                doc.getChunkIndex(),
                doc.getTokens(),
                doc.getVectorId(),
                doc.getCreatedAt()
        );
    }
}
