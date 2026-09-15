package com.ai.rag.service;

import com.ai.rag.model.dto.RetrievalHit;
import com.ai.rag.model.entity.Document;
import com.ai.rag.model.entity.KnowledgeBase;
import com.ai.rag.repository.DocumentRepository;
import com.ai.rag.repository.KnowledgeBaseRepository;
import com.ai.rag.service.retrieval.RrfFusion;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RAG检索服务
 *
 * <p>Stage 2：由「向量单路召回，失败才退化成关键词」升级为**多路召回 + RRF 融合**：
 * <ul>
 *   <li>路 1 —— 向量语义召回：Milvus ANN（不可用时由 {@link dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore} 降级承载）</li>
 *   <li>路 2 —— 关键词稀疏召回：标题/内容 LIKE 匹配，中文按 2-gram 扩展提升命中率</li>
 * </ul>
 * 两路各自独立召回后，由 {@link RrfFusion} 按倒数排名融合成统一排名，
 * 避免余弦相似度与关键词命中这类不同尺度分数无法直接加权的问题。
 *
 * <p>所有配置项见 {@code rag.retrieval.*}。每一路失败只降级该路（返回空列表），不影响另一路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    /** 融合结果中各路的固定下标（与 {@link #searchWithFusion} 传入顺序一致） */
    private static final int ROUTE_VECTOR = 0;
    private static final int ROUTE_KEYWORD = 1;

    /** 关键词切分符：空白与中英文标点 */
    private static final Pattern TERM_SPLITTER =
            Pattern.compile("[\\s,，、。;；:：!！?？\"'“”‘’()（）\\[\\]【】{}<>《》/\\\\|]+");

    private static final int CJK_RANGE_START = 0x4E00;
    private static final int CJK_RANGE_END = 0x9FFF;

    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    /** 向量路召回深度 */
    @Value("${rag.retrieval.vector-top-k:20}")
    private int vectorTopK;

    /** 关键词路召回深度 */
    @Value("${rag.retrieval.keyword-top-k:20}")
    private int keywordTopK;

    /** 关键词扩展后的最大 term 数（每个 term 一次查询，用于控制 DB 往返） */
    @Value("${rag.retrieval.max-keyword-terms:8}")
    private int maxKeywordTerms;

    /** 向量召回最低相似度，低于该值的分块不进入融合 */
    @Value("${rag.retrieval.min-score:0.3}")
    private double minScore;

    /** RRF 平滑常数 */
    @Value("${rag.retrieval.rrf-k:60}")
    private int rrfK;

    /**
     * 检索相关知识（仅返回文档，保持既有调用方契约）
     */
    public List<Document> searchRelevantDocuments(String query, Long knowledgeBaseId, int topK) {
        return searchWithFusion(query, knowledgeBaseId, topK).stream()
                .map(RetrievalHit::document)
                .toList();
    }

    /**
     * 多路召回 + RRF 融合检索
     *
     * @param query           查询文本
     * @param knowledgeBaseId 限定知识库，null 表示全库
     * @param topK            融合后返回条数
     * @return 按融合分降序排列的命中结果（含各路排名，便于观测与调参）
     */
    public List<RetrievalHit> searchWithFusion(String query, Long knowledgeBaseId, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }

        // 多路并行召回（两路各自容错，互不影响）
        List<Document> vectorHits = vectorRecall(query, knowledgeBaseId);
        List<Document> keywordHits = keywordRecall(query, knowledgeBaseId);

        if (vectorHits.isEmpty() && keywordHits.isEmpty()) {
            log.info("Multi-recall found nothing for query: {}", query);
            return List.of();
        }

        // RRF 融合：以 Document.id 作为跨路去重标识
        List<RrfFusion.Fused<Document>> fused = RrfFusion.fuse(
                List.of(vectorHits, keywordHits), rrfK, topK, Document::getId);

        List<RetrievalHit> hits = fused.stream()
                .map(f -> new RetrievalHit(f.item(), f.score(),
                        f.ranks().get(ROUTE_VECTOR), f.ranks().get(ROUTE_KEYWORD)))
                .toList();

        log.info("Multi-recall fused: vector={}, keyword={}, returned={}, topSource={}",
                vectorHits.size(), keywordHits.size(), hits.size(),
                hits.isEmpty() ? "-" : hits.get(0).source());

        return hits;
    }

    // ------------------------------------------------------------------
    // 路 1：向量语义召回
    // ------------------------------------------------------------------

    /**
     * 向量召回：ANN 检索 -> 按向量命中顺序重排文档
     */
    private List<Document> vectorRecall(String query, Long knowledgeBaseId) {
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();

            EmbeddingSearchRequest.EmbeddingSearchRequestBuilder builder = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(vectorTopK)
                    .minScore(minScore);

            Filter filter = buildKnowledgeBaseFilter(knowledgeBaseId);
            if (filter != null) {
                builder.filter(filter);
            }

            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(builder.build());

            // 按相似度降序的向量 ID 列表
            List<String> vectorIds = result.matches().stream()
                    .map(EmbeddingMatch::embeddingId)
                    .collect(Collectors.toList());

            if (vectorIds.isEmpty()) {
                return List.of();
            }

            // DB 在 SQL 层按知识库过滤，避免 LAZY 关联在事务外被访问
            List<Document> documents = knowledgeBaseId != null
                    ? documentRepository.findByVectorIdInAndKnowledgeBaseId(vectorIds, knowledgeBaseId)
                    : documentRepository.findByVectorIdIn(vectorIds);

            return rankByVectorOrder(documents, vectorIds);

        } catch (Exception e) {
            log.warn("向量召回失败，本路降级为空：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 按向量命中顺序重排文档（DB 返回的顺序与 ANN 相关性顺序无关）
     */
    private List<Document> rankByVectorOrder(List<Document> documents, List<String> vectorIds) {
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < vectorIds.size(); i++) {
            order.putIfAbsent(vectorIds.get(i), i);
        }

        return documents.stream()
                .sorted(Comparator.comparingInt(doc -> order.getOrDefault(doc.getVectorId(), Integer.MAX_VALUE)))
                .limit(vectorTopK)
                .toList();
    }

    /**
     * 构造按知识库过滤的向量检索条件
     *
     * 元数据字段 {@code knowledgeBaseId} 由 {@code DocumentService} 入库时写入。
     * 若向量库不支持过滤（如旧集合缺少该标量字段），返回 null 退化为不过滤。
     */
    private Filter buildKnowledgeBaseFilter(Long knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            return null;
        }
        try {
            return MetadataFilterBuilder.metadataKey("knowledgeBaseId")
                    .isEqualTo(String.valueOf(knowledgeBaseId));
        } catch (Exception e) {
            log.warn("构造知识库过滤条件失败，退化为不过滤：{}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 路 2：关键词稀疏召回
    // ------------------------------------------------------------------

    /**
     * 关键词召回：切分查询 -> 逐 term 检索 -> 按首次命中顺序合并去重
     */
    private List<Document> keywordRecall(String query, Long knowledgeBaseId) {
        List<String> terms = extractTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        Map<Long, Document> merged = new LinkedHashMap<>();
        for (String term : terms) {
            for (Document doc : searchByKeyword(term, knowledgeBaseId)) {
                if (doc.getId() != null) {
                    merged.putIfAbsent(doc.getId(), doc);
                }
            }
            if (merged.size() >= keywordTopK) {
                break;
            }
        }

        return merged.values().stream()
                .limit(keywordTopK)
                .toList();
    }

    /**
     * 查询词切分
     *
     * 英文按空白/标点切分；中文等无空格语言额外补充 2-gram
     * （如「检索私有知识库」→ 检索/索私/私有/有知/知识/识库），提升 LIKE 命中率。
     */
    private List<String> extractTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();

        for (String token : TERM_SPLITTER.split(query.trim())) {
            if (token.isBlank()) {
                continue;
            }
            if (terms.size() >= maxKeywordTerms) {
                break;
            }
            terms.add(token);

            if (containsCjk(token) && token.length() > 2) {
                for (int i = 0; i + 2 <= token.length() && terms.size() < maxKeywordTerms; i++) {
                    terms.add(token.substring(i, i + 2));
                }
            }
        }

        return List.copyOf(terms);
    }

    /**
     * 单个 term 的关键词检索（限定知识库 / 全库）
     */
    private List<Document> searchByKeyword(String term, Long knowledgeBaseId) {
        try {
            return knowledgeBaseId != null
                    ? documentRepository.searchByKeyword(knowledgeBaseId, term)
                    : documentRepository.searchByKeywordAll(term);
        } catch (Exception e) {
            log.warn("关键词召回失败（term={}），本 term 跳过：{}", term, e.getMessage());
            return List.of();
        }
    }

    /**
     * 是否包含 CJK 字符
     */
    private boolean containsCjk(String text) {
        return text.codePoints().anyMatch(cp -> cp >= CJK_RANGE_START && cp <= CJK_RANGE_END);
    }

    // ------------------------------------------------------------------
    // 检索结果后处理
    // ------------------------------------------------------------------

    /**
     * 构建检索上下文
     */
    public String buildSearchContext(List<Document> documents) {
        if (documents.isEmpty()) {
            return "没有找到相关的知识库信息。";
        }

        StringBuilder context = new StringBuilder();
        context.append("以下是与问题相关的知识库信息：\n\n");

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            context.append(String.format(
                "【文档%d】%s\n%s\n\n",
                i + 1,
                doc.getTitle() != null ? doc.getTitle() : "未知标题",
                doc.getContent()
            ));
        }

        return context.toString();
    }

    /**
     * 计算检索覆盖率
     */
    public double calculateCoverage(String query, List<Document> retrievedDocuments, Long knowledgeBaseId) {
        if (retrievedDocuments.isEmpty()) {
            return 0.0;
        }

        // 简单覆盖率计算：基于查询词在文档中的出现频率
        String[] queryTerms = query.toLowerCase().split("\\s+");
        int totalTerms = queryTerms.length;
        int coveredTerms = 0;

        Set<String> documentTerms = retrievedDocuments.stream()
            .flatMap(doc -> Arrays.stream(doc.getContent().toLowerCase().split("\\s+")))
            .collect(Collectors.toSet());

        for (String term : queryTerms) {
            if (documentTerms.contains(term)) {
                coveredTerms++;
            }
        }

        return (double) coveredTerms / totalTerms;
    }

    /**
     * 更新知识库统计
     */
    public void updateKnowledgeBaseStats(Long knowledgeBaseId) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(knowledgeBaseId)
            .orElseThrow(() -> new RuntimeException("Knowledge base not found"));

        long docCount = documentRepository.countByKnowledgeBaseId(knowledgeBaseId);
        long totalTokens = documentRepository.sumTokensByKnowledgeBaseId(knowledgeBaseId);

        kb.setDocCount((int) docCount);
        kb.setTotalTokens((int) totalTokens);
        knowledgeBaseRepository.save(kb);

        log.info("Updated knowledge base stats: docCount={}, totalTokens={}", docCount, totalTokens);
    }

    /**
     * 获取知识库统计信息
     */
    public Map<String, Object> getKnowledgeBaseStats(Long knowledgeBaseId) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(knowledgeBaseId)
            .orElseThrow(() -> new RuntimeException("Knowledge base not found"));

        long docCount = documentRepository.countByKnowledgeBaseId(knowledgeBaseId);
        long totalTokens = documentRepository.sumTokensByKnowledgeBaseId(knowledgeBaseId);

        Map<String, Object> stats = new HashMap<>();
        stats.put("knowledgeBaseName", kb.getName());
        stats.put("documentCount", docCount);
        stats.put("totalTokens", totalTokens);
        stats.put("averageTokensPerDocument", docCount > 0 ? totalTokens / docCount : 0);
        stats.put("createdAt", kb.getCreatedAt());
        stats.put("updatedAt", kb.getUpdatedAt());

        return stats;
    }
}
