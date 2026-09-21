package com.ai.rag.service.retrieval;

import com.ai.rag.model.dto.RetrievalHit;
import com.ai.rag.model.entity.Document;
import com.ai.rag.service.RagService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 混合检索内容注入器（Stage 2）
 *
 * <p>把 {@link RagService} 的「多路召回 + RRF 融合」结果转换为 LangChain4j 的 {@link Content}，
 * 在 AiServices 对话时自动注入 prompt，替代原先只走向量单路召回的
 * {@code EmbeddingStoreContentRetriever}。
 *
 * <p>注入的 {@link TextSegment} 携带
 * {@code documentId}/{@code chunkId}/{@code rrfScore}/{@code source}/{@code vectorRank}/{@code keywordRank}
 * 元数据，便于后续做引用溯源（Stage 4 评测 / 前端展示引用编号与分路排名）。
 *
 * <p>检索失败时返回空列表（本轮不做知识注入），不影响对话主流程。
 */
@Slf4j
@RequiredArgsConstructor
public class HybridContentRetriever implements ContentRetriever {

    private final RagService ragService;
    private final int maxResults;

    @Override
    public List<Content> retrieve(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return List.of();
        }

        try {
            List<RetrievalHit> hits = ragService.searchWithFusion(query.text(), null, maxResults);
            if (hits.isEmpty()) {
                return List.of();
            }

            log.debug("Hybrid retrieval injected {} chunks for query: {}", hits.size(), query.text());
            return hits.stream()
                    .map(hit -> Content.from(toTextSegment(hit)))
                    .toList();

        } catch (Exception e) {
            log.warn("混合检索失败，本轮不做知识注入：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 命中结果 -> 带溯源元数据的文本分块
     */
    private TextSegment toTextSegment(RetrievalHit hit) {
        Document doc = hit.document();
        Metadata metadata = new Metadata()
                .put("documentId", doc.getId() == null ? "" : String.valueOf(doc.getId()))
                .put("chunkId", doc.getChunkId() == null ? "" : doc.getChunkId())
                .put("rrfScore", String.format("%.6f", hit.score()))
                .put("source", hit.source())
                // 分路排名一并带出去。此前只写 rrfScore/source，前端拿不到「这一条是向量第几、
                // 关键词第几」，聊天里的引用面板就画不出泳道 —— 而向量路静默失效恰恰要靠它才看得出来。
                // 0 表示该路未召回，是有意义的取值，不要当成缺失。（Metadata 只存字符串，读侧再转回数字）
                .put("vectorRank", String.valueOf(hit.vectorRank()))
                .put("keywordRank", String.valueOf(hit.keywordRank()));

        return TextSegment.from(doc.getContent(), metadata);
    }
}
