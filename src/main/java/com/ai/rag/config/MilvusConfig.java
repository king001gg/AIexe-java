package com.ai.rag.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 向量库配置类
 *
 * 基于 LangChain4j 官方的 {@link MilvusEmbeddingStore} 提供 {@link EmbeddingStore}：
 * - 集合创建、HNSW 索引构建、向量插入/检索全部由官方实现封装
 * - 当 Milvus 不可用（连接失败）时，优雅降级为内存向量库，不影响主流程
 */
@Slf4j
@Configuration
public class MilvusConfig {

    @Value("${milvus.host}")
    private String host;

    @Value("${milvus.port}")
    private int port;

    @Value("${milvus.database}")
    private String database;

    @Value("${milvus.collection.name}")
    private String collectionName;

    @Value("${milvus.collection.dimension}")
    private int dimension;

    @Value("${milvus.collection.metric-type:COSINE}")
    private String metricType;

    @Value("${milvus.collection.index-type:HNSW}")
    private String indexType;

    /**
     * 向量存储（Milvus 优先，不可用时回退内存）
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        try {
            MilvusEmbeddingStore store = MilvusEmbeddingStore.builder()
                    .host(host)
                    .port(port)
                    .collectionName(collectionName)
                    .dimension(dimension)
                    .indexType(resolveIndexType())
                    .metricType(resolveMetricType())
                    .databaseName(database)
                    .build();
            log.info("Milvus 向量库已初始化：collection={}, dimension={}", collectionName, dimension);
            return store;
        } catch (Exception e) {
            log.warn("Milvus 不可用，回退内存向量库：{}", e.getMessage());
            return new InMemoryEmbeddingStore<>();
        }
    }

    private MetricType resolveMetricType() {
        try {
            return MetricType.valueOf(metricType.trim().toUpperCase());
        } catch (Exception e) {
            log.warn("未知的 Milvus 度量类型：{}，回退为 COSINE", metricType);
            return MetricType.COSINE;
        }
    }

    private IndexType resolveIndexType() {
        try {
            return IndexType.valueOf(indexType.trim().toUpperCase());
        } catch (Exception e) {
            log.warn("未知的 Milvus 索引类型：{}，回退为 HNSW", indexType);
            return IndexType.HNSW;
        }
    }
}
