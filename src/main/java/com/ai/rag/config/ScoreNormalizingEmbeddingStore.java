package com.ai.rag.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 把向量库的 score 口径统一成「余弦相似度」的装饰器（缺陷 D3）
 *
 * <p><b>问题：同一个 {@code rag.retrieval.min-score} 在两个 store 上含义不同。</b>
 * 两者都是「{@code score >= minScore} 就保留」，但 score 本身不是一回事：
 *
 * <table border="1">
 *   <caption>两个 store 的 score 语义</caption>
 *   <tr><th>store</th><th>score</th><th>范围</th></tr>
 *   <tr><td>{@code MilvusEmbeddingStore}（COSINE）</td><td><b>原始余弦</b></td><td>[-1, 1]</td></tr>
 *   <tr><td>{@link InMemoryEmbeddingStore}（Milvus 不可用时的降级路径）</td>
 *       <td>{@code RelevanceScore.fromCosineSimilarity()} = {@code (cosine+1)/2}</td><td>[0, 1]</td></tr>
 * </table>
 *
 * <p>于是生产配置 {@code min-score: 0.3} 在 Milvus 下是「余弦 ≥ 0.3」（严格过滤），
 * 在降级路径下却是「余弦 ≥ <b>-0.4</b>」——等于把阈值关掉了，会把与问题无关的分块
 * 一起注入 prompt。降级本该是「能力降级」，却变成了「结果质量静默劣化」。
 *
 * <p><b>做法：</b>对降级 store 做双向换算——把传下去的 {@code minScore} 从余弦口径换成
 * {@code (cosine+1)/2}，再把返回的 score 换回原始余弦。对外（也即对
 * {@code RagService}）暴露的语义因此与 Milvus 完全一致：
 * <b>{@code min-score} 一律表示「余弦 ≥ X」</b>。
 *
 * <p><b>等价性覆盖整个合法输入域：</b>{@code EmbeddingSearchRequest} 会把
 * {@code minScore} 校验到 {@code [0.0, 1.0]}（闭区间，见
 * {@code ValidationUtils.ensureBetween}）。换算 {@code (c+1)/2} 恰好把 {@code [0,1]} 映射到
 * {@code [0.5, 1]} ⊆ {@code [0,1]}，所以**每一个法律上可表达的阈值**都与 Milvus 同义，
 * 没有边角漏网。
 *
 * <p>推论：{@code minScore = 0.0} 的含义是「余弦 ≥ 0」而**不是**「不过滤」——
 * 注意负余弦阈值在这个 API 里**根本表达不出来**（{@code -0.1} 会被
 * {@code EmbeddingSearchRequest} 直接拒绝），所以「让降级库全量返回」这件事在
 * Milvus 上同样做不到。两侧一致，正是本适配器要的结果。
 *
 * <p><b>不适用范围：</b>只有「非余弦度量」不在此列。Milvus 配成 L2/IP 时 SDK 返回的
 * 既不是余弦，LangChain4j 侧仍是裸比较，本装饰器不做猜测（{@code EmbeddingStoreScoreConfig}
 * 会在启动时对此告警）。
 *
 * @see EmbeddingStoreScoreConfig
 */
public class ScoreNormalizingEmbeddingStore implements EmbeddingStore<TextSegment> {

    private final EmbeddingStore<TextSegment> delegate;

    /** 代理是否返回 RelevanceScore（当前仅降级用的内存实现如此），构造期判定一次 */
    private final boolean relevanceScored;

    public ScoreNormalizingEmbeddingStore(EmbeddingStore<TextSegment> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.relevanceScored = delegate instanceof InMemoryEmbeddingStore;
    }

    /**
     * 包一层；已包过的原样返回，避免重复换算把分数彻底改坏
     */
    public static ScoreNormalizingEmbeddingStore wrap(EmbeddingStore<TextSegment> delegate) {
        if (delegate instanceof ScoreNormalizingEmbeddingStore alreadyWrapped) {
            return alreadyWrapped;
        }
        return new ScoreNormalizingEmbeddingStore(delegate);
    }

    /** 被包装的原始 store（测试与排障用） */
    public EmbeddingStore<TextSegment> delegate() {
        return delegate;
    }

    // ------------------------------------------------------------------
    // 检索：唯一需要换算的地方
    // ------------------------------------------------------------------

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        if (!relevanceScored) {
            // 已是余弦口径（Milvus COSINE），原样透传——不引入任何多余改动
            return delegate.search(request);
        }

        EmbeddingSearchRequest relevanceRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(request.queryEmbedding())
                .maxResults(request.maxResults())
                // 余弦阈值 → RelevanceScore 阈值，让下游按它自己的口径过滤
                .minScore(toRelevanceScore(request.minScore()))
                .filter(request.filter())
                .build();

        List<EmbeddingMatch<TextSegment>> normalized = delegate.search(relevanceRequest).matches().stream()
                .map(match -> new EmbeddingMatch<>(
                        toCosineSimilarity(match.score()),
                        match.embeddingId(),
                        match.embedding(),
                        match.embedded()))
                .toList();

        return new EmbeddingSearchResult<>(normalized);
    }

    static double toRelevanceScore(double cosineSimilarity) {
        return (cosineSimilarity + 1) / 2;
    }

    static double toCosineSimilarity(double relevanceScore) {
        return relevanceScore * 2 - 1;
    }

    // ------------------------------------------------------------------
    // 写入与删除：纯委托
    //
    // 注意 remove* 四个方法在接口里是 default 实现（未实现时会抛
    // UnsupportedOperationException）。**必须显式委托**，否则
    // DocumentService.deleteVectorFromEmbeddingStore 的 embeddingStore.remove(...)
    // 会抛异常，而它正好在自己的 catch (Exception) 里把异常吞成一行 warn 日志——
    // 表现为「删了知识库，向量还留在库里」，且没有任何测试会红。
    // ------------------------------------------------------------------

    @Override
    public String add(Embedding embedding) {
        return delegate.add(embedding);
    }

    @Override
    public void add(String id, Embedding embedding) {
        delegate.add(id, embedding);
    }

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        return delegate.add(embedding, embedded);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        return delegate.addAll(embeddings);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> embedded) {
        return delegate.addAll(embeddings, embedded);
    }

    @Override
    public void remove(String id) {
        delegate.remove(id);
    }

    @Override
    public void removeAll(Collection<String> ids) {
        delegate.removeAll(ids);
    }

    @Override
    public void removeAll(Filter filter) {
        delegate.removeAll(filter);
    }

    @Override
    public void removeAll() {
        delegate.removeAll();
    }
}
