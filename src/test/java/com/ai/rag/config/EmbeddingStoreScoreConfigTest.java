package com.ai.rag.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配层的守卫（缺陷 D3）：无论 {@code metric-type} 是什么，业务代码拿到的都是同口径的包装器
 *
 * <p>不启 Spring——本类要验的正是「这个方法在任何参数下都不改变行为」，直接调比起上下文更清楚。
 */
class EmbeddingStoreScoreConfigTest {

    private final EmbeddingStoreScoreConfig config = new EmbeddingStoreScoreConfig();

    @Test
    @DisplayName("D3：metric-type 非 COSINE 时只告警、不改行为——依然返回包装器，不静默退化成原始 store")
    void nonCosineMetricWarnsButStillWraps() {
        InMemoryEmbeddingStore<TextSegment> raw = new InMemoryEmbeddingStore<>();

        EmbeddingStore<TextSegment> result = config.cosineScoredEmbeddingStore(raw, "L2");

        // 「只告警不改行为」是当初选定的方案，这条用例把「告警不等于撤掉适配器」钉住：
        // L2 度量下 Milvus 返回的是距离而非相似度，min-score 的比较口径无法保证，
        // 但那属于**配置错误**，要靠日志让人去改配置；此处擅自换成不包装或抛异常，
        // 都会把一个配置问题变成启动/运行期故障。
        assertThat(result)
                .as("告警分支不能顺手把适配器摘掉")
                .isInstanceOf(ScoreNormalizingEmbeddingStore.class);
        assertThat(((ScoreNormalizingEmbeddingStore) result).delegate()).isSameAs(raw);
    }

    @Test
    @DisplayName("D3：metric-type 为 COSINE（含大小写变体）时同样返回包装器")
    void cosineMetricWraps() {
        InMemoryEmbeddingStore<TextSegment> raw = new InMemoryEmbeddingStore<>();

        for (String metricType : new String[]{"COSINE", "cosine", "Cosine"}) {
            assertThat(config.cosineScoredEmbeddingStore(raw, metricType))
                    .as("metric-type=%s", metricType)
                    .isInstanceOf(ScoreNormalizingEmbeddingStore.class);
        }
    }
}
