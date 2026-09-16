package com.ai.rag.model.dto;

import com.ai.rag.model.entity.KnowledgeBase;

import java.time.LocalDateTime;

/**
 * 知识库摘要 —— 读接口的响应体
 *
 * <p><b>为什么不直接返回 {@link KnowledgeBase} 实体（缺陷 D8）：</b>
 * 实体上有一条 {@code @OneToMany(fetch = LAZY) List<Document> documents}。
 * 控制器返回实体时，Jackson 序列化会调用 {@code getDocuments()}，
 * 而此时事务已提交、Session 已关闭（{@code open-in-view: false}），
 * 于是抛 {@code LazyInitializationException} → 整个端点 500。
 *
 * <p>用 DTO 而不是「打开 open-in-view」或「加 @JsonIgnore」来解决，理由是
 * <b>API 契约不应随实体结构漂移</b>：实体加一个关联字段就可能改变响应体甚至打挂接口。
 * DTO 把对外契约钉死，实体怎么改都不会外溢。
 * 同类的正确写法在 {@code /documents/search/detailed} 里已有现成范本。
 *
 * <p>{@code filePath} 是服务端本地路径，属内部信息，刻意不对外暴露。
 */
public record KnowledgeBaseSummary(
        Long id,
        String name,
        String description,
        String fileName,
        Long fileSize,
        Integer docCount,
        Integer totalTokens,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static KnowledgeBaseSummary from(KnowledgeBase kb) {
        return new KnowledgeBaseSummary(
                kb.getId(),
                kb.getName(),
                kb.getDescription(),
                kb.getFileName(),
                kb.getFileSize(),
                kb.getDocCount(),
                kb.getTotalTokens(),
                kb.getCreatedBy(),
                kb.getCreatedAt(),
                kb.getUpdatedAt()
        );
    }
}
