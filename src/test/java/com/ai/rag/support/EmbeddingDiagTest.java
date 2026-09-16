package com.ai.rag.support;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺陷 D3 的快照测试：降级向量库的 min-score 语义与 Milvus 不一致
 *
 * <p>锁定的行为（已实测）：
 * <ul>
 *   <li>{@link InMemoryEmbeddingStore} 的 score 不是余弦相似度，而是
 *       {@code (cosine + 1) / 2}（把 [-1,1] 线性映射到 [0,1]）；
 *       余弦 0.0 的**完全无关**文本，score 是 0.5，不是 0。</li>
 *   <li>过滤条件是 {@code score >= minScore}。</li>
 * </ul>
 *
 * <p>后果：生产配置 {@code rag.retrieval.min-score: 0.3} 在 Milvus（COSINE，返回原始余弦）
 * 下等价于「余弦 ≥ 0.3」，而在无 Milvus 的降级路径下等价于「余弦 ≥ -0.4」——等于关闭了阈值。
 * 同一份配置，主路径严格过滤、降级路径几乎全量返回，降级时会往 prompt 里注入不相关内容。
 *
 * <p>本测试不评价「该不该这样」（那是 LangChain4j 的实现选择），只把差异固化成可执行证据。
 * 一旦上游修改语义或本项目改写降级实现，这里会立刻失败提醒复核。
 */
class EmbeddingDiagTest {

    private static final String UNRELATED_QUERY = "请背诵一段完全无关的莎士比亚台词";
    private static final String DOC = "Milvus 向量库的默认端口是 19530。";

    @Test
    @DisplayName("缺陷 D3：完全无关文本在降级向量库里的 score 是 0.5（不是 0），min-score 需按 (cos+1)/2 理解")
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
