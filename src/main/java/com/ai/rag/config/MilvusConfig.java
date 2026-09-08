package com.ai.rag.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Milvus向量数据库配置类
 *
 * 配置：
 * - Milvus连接参数
 * - 集合创建
 * - 字段定义
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

    @Value("${milvus.collection.metric-type}")
    private String metricType;

    @Value("${milvus.collection.index-type}")
    private String indexType;

    /**
     * 创建Milvus客户端
     * local 环境不创建（避免启动时连接失败）
     */
    @Bean
    @Profile("!local")
    public MilvusServiceClient milvusClient() {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .build();

        return new MilvusServiceClient(connectParam);
    }

    /**
     * 初始化向量集合
     * 在Bean初始化完成后执行
     */
    @PostConstruct
    public void initMilvusCollection() {
        log.info("Initializing Milvus collection: {}", collectionName);
        // 注意：实际连接Milvus时再创建集合，这里仅记录日志
        // 避免在无Milvus环境时启动失败
    }
}