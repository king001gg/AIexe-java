package com.ai.rag.support;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺陷 D3 的**根因证据**：上游降级向量库的 score 语义与 Milvus 不一致
 *
 * <p>注意本测试测的是**裸的** {@link InMemoryEmbeddingStore}，不走 Spring、也不经过
 * 本项目的适配器——它固化的是「**上游库**为什么需要适配」这个事实，而不是本项目的对外行为。
 * 本项目对外的完整口径由 {@code ScoreNormalizingEmbeddingStoreTest} 守卫
 * （那里断言包装后 {@code min-score} 是余弦口径、返回的 score 是原始余弦）。
 *
 * <p>锁定的上游行为（已实测）：
 * <ul>
 *   <li>{@link InMemoryEmbeddingStore} 的 score 不是余弦相似度，而是
 *       {@code (cosine + 1) / 2}（把 [-1,1] 线性映射到 [0,1]）；
 *       余弦 0.0 的**完全无关**文本，score 是 0.5，不是 0。</li>
 *   <li>过滤条件是 {@code score >= minScore}。</li>
 * </ul>
 *
 * <p>后果（D3 本体，已修复）：生产配置 {@code rag.retrieval.min-score: 0.3} 在
 * Milvus（COSINE，返回原始余弦）下等价于「余弦 ≥ 0.3」，而在无 Milvus 的降级路径下
 * 等价于「余弦 ≥ -0.4」——等于关闭了阈值，会往 prompt 里注入不相关内容。
 * 修复方式是 {@code config/ScoreNormalizingEmbeddingStore}：在 store 边界把口径统一成余弦。
 *
 * <p><b>为什么保留这个测试：</b>它是适配器存在的理由。一旦上游修改了这套语义
 * （或本项目不再使用内存库降级），适配器的换算前提就变了，这里会立刻失败提醒复核——
 * 这是「删掉一个过时适配器」的触发器，而不是历史包袱。
 */
class EmbeddingDiagTest {

    private static final String UNRELATED_QUERY = "请背诵一段完全无关的莎士比亚台词";
    private static final String DOC = "Milvus 向量库的默认端口是 19530。";

    @Test
    @DisplayName("D3 根因（上游库语义）：完全无关文本在降级向量库里的 score 是 0.5（不是 0），故需适配器换算")
    void inMemoryStoreUsesShiftedCosineAsScore() {
        DeterministicEmbeddingModel model = new DeterministicEmbeddingModel();
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        store.add(model.embedText(DOC), TextSegment.from(DOC));

        Embedding query = model.embedText(UNRELATED_QUERY);
        double rawCosine = dot(query, model.embedText(DOC));
        store.add(model.embedText(DOC), TextSegment.from(DOC + "（副本）"));

        assertThat(rawCosine)
                .as("词袋桩下两者没有任何共享词元，原始余弦应为 0")
                .isEqualTo(0.0);

        assertThat(scoreAt(store, query, 0.5))
                .as("min-score=0.5 时仍能召回，说明 score 被抬到了 0.5")
                .isPositive();
        assertThat(scoreAt(store, query, 0.6))
                .as("min-score=0.6 时才被挡住，进一步确认 score 恰为 0.5")
                .isZero();
    }

    private static int scoreAt(InMemoryEmbeddingStore<TextSegment> store, Embedding query, double minScore) {
        return store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(query)
                .maxResults(20)
                .minScore(minScore)
                .build()).matches().size();
    }

    private static float dot(Embedding left, Embedding right) {
        float[] l = left.vector();
        float[] r = right.vector();
        float sum = 0f;
        for (int i = 0; i < l.length; i++) {
            sum += l[i] * r[i];
        }
        return sum;
    }
}
