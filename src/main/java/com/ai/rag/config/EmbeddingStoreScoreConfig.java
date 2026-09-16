package com.ai.rag.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 向量库 score 口径的装配（缺陷 D3）
 *
 * <p><b>为什么是在这里包一层，而不是在 {@code MilvusConfig.embeddingStore()} 里包：</b>
 * 测试上下文用 {@code StubModelsConfig.replaceMilvusWithInMemoryStore()} 这个
 * {@code BeanFactoryPostProcessor} <b>按名字删掉并重新注册</b>了 {@code embeddingStore}
 * Bean 定义（原因见那边的注释：被冷落的 Milvus Bean 在实例化阶段照样会阻塞约 10 秒）。
 * 若把包装写进那个 Bean 方法，测试上下文里这段逻辑**根本不会被执行**，测试会继续跑在
 * 未归一化的 store 上——D3 就只在测试里「看起来修好了」，而真实降级路径依旧漏过滤。
 *
 * <p>因此保持 {@code embeddingStore} 为**原始 store**，另加一个 {@code @Primary} 包装 Bean：
 * 业务代码按类型注入拿到包装器（{@code RagService} / {@code DocumentService} 都是构造注入），
 * 而按名字替换/按名字注入的地方不受影响。生产包 Milvus、测试包内存实现，两条路径都生效。
 *
 * @see ScoreNormalizingEmbeddingStore
 */
@Slf4j
@Configuration
public class EmbeddingStoreScoreConfig {

    /**
     * 对外（业务代码）生效的向量库：score 一律是余弦相似度
     *
     * <p>{@code @Primary} 与 {@code @Qualifier} 同时出现是有意的：{@code @Qualifier} 指明
     * 被包装的是哪个 Bean（否则按类型会选中自己，形成自引用），{@code @Primary} 决定
     * 「按类型注入 EmbeddingStore 时用谁」。
     */
    @Bean
    @Primary
    public EmbeddingStore<TextSegment> cosineScoredEmbeddingStore(
            @Qualifier("embeddingStore") EmbeddingStore<TextSegment> delegate,
            @Value("${milvus.collection.metric-type:COSINE}") String metricType) {

        if (!"COSINE".equalsIgnoreCase(metricType)) {
            log.warn("milvus.collection.metric-type={} 不是 COSINE：向量库返回的不是余弦相似度，"
                            + "rag.retrieval.min-score 的比较口径无法保证，min-score 需按该度量的实际语义重新评估",
                    metricType);
        }

        log.info("向量库 score 口径统一为余弦相似度（min-score 即「余弦 ≥ X」），实际代理：{}",
                delegate.getClass().getSimpleName());

        return ScoreNormalizingEmbeddingStore.wrap(delegate);
    }
}
