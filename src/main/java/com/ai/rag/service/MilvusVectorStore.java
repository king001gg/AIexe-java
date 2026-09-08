package com.ai.rag.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.RpcStatus;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Milvus 向量存储服务
 *
 * 封装 milvus-sdk-java 的底层操作：
 * - 集合创建（id 主键 + FloatVector 字段）
 * - HNSW 索引构建
 * - 向量插入 / 删除
 * - ANN 近似最近邻检索
 *
 * 当 Milvus 不可用（local 环境未注册 MilvusServiceClient，或连接失败）时，
 * 所有操作优雅降级：记录 warning 并返回空结果，不影响主流程。
 */
@Slf4j
@Service
public class MilvusVectorStore {

    /** 向量字段名 */
    public static final String VECTOR_FIELD = "vector";
    /** 主键字段名 */
    public static final String ID_FIELD = "id";

    private final ObjectProvider<MilvusServiceClient> clientProvider;

    @Value("${milvus.collection.name}")
    private String collectionName;

    @Value("${milvus.collection.dimension}")
    private int dimension;

    @Value("${milvus.collection.metric-type:COSINE}")
    private String metricType;

    @Value("${milvus.collection.index-type:HNSW}")
    private String indexType;

    /** 集合是否已确保创建（懒初始化，线程安全） */
    private volatile boolean collectionEnsured = false;

    public MilvusVectorStore(ObjectProvider<MilvusServiceClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    /**
     * 判断 Milvus 是否可用
     */
    public boolean isAvailable() {
        return clientProvider.getIfAvailable() != null;
    }

    /**
     * 插入向量
     *
     * @param id     向量唯一标识（对应 Document.vectorId）
     * @param vector 向量值
     */
    public void insert(String id, float[] vector) {
        MilvusServiceClient client = client();
        if (client == null) {
            log.warn("Milvus 不可用，跳过向量插入：id={}", id);
            return;
        }
        try {
            ensureCollection(client);

            JSONObject row = new JSONObject();
            row.put(ID_FIELD, id);

            JSONArray vectorArr = new JSONArray();
            for (float v : vector) {
                vectorArr.add(v);
            }
            row.put(VECTOR_FIELD, vectorArr);

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withRows(Collections.singletonList(row))
                    .build();

            R<MutationResult> result = client.insert(insertParam);
            if (result.getStatus() != 0) {
                log.warn("Milvus 向量插入失败：{}", result.getMessage());
            } else {
                log.debug("Milvus 向量已插入：id={}", id);
            }
        } catch (Exception e) {
            log.warn("Milvus 向量插入异常：{}", e.getMessage());
        }
    }

    /**
     * ANN 近似最近邻检索
     *
     * @param queryVector 查询向量
     * @param topK        返回数量
     * @return 按相似度降序排列的检索结果
     */
    public List<SearchHit> search(float[] queryVector, int topK) {
        MilvusServiceClient client = client();
        if (client == null) {
            log.warn("Milvus 不可用，跳过向量检索");
            return Collections.emptyList();
        }
        try {
            ensureCollection(client);

            List<List<Float>> queryVectors = new ArrayList<>(1);
            List<Float> qv = new ArrayList<>(queryVector.length);
            for (float v : queryVector) {
                qv.add(v);
            }
            queryVectors.add(qv);

            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withMetricType(resolveMetricType())
                    .withTopK(topK)
                    .withVectorFieldName(VECTOR_FIELD)
                    .withParams(resolveSearchParams())
                    .withVectors(queryVectors)
                    .withOutFields(Collections.singletonList(ID_FIELD))
                    .build();

            R<SearchResults> result = client.search(searchParam);
            if (result.getStatus() != 0 || result.getData() == null) {
                log.warn("Milvus 向量检索失败：{}", result.getMessage());
                return Collections.emptyList();
            }

            SearchResultsWrapper wrapper = new SearchResultsWrapper(result.getData().getResults());
            List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);

            return idScores.stream()
                    .map(item -> new SearchHit(item.getStrID(), item.getScore()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Milvus 向量检索异常：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 删除向量
     *
     * @param id 向量唯一标识
     */
    public void delete(String id) {
        MilvusServiceClient client = client();
        if (client == null) {
            log.warn("Milvus 不可用，跳过向量删除：id={}", id);
            return;
        }
        try {
            ensureCollection(client);

            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr(String.format("%s in [\"%s\"]", ID_FIELD, id))
                    .build();

            R<MutationResult> result = client.delete(deleteParam);
            if (result.getStatus() != 0) {
                log.warn("Milvus 向量删除失败：{}", result.getMessage());
            } else {
                log.debug("Milvus 向量已删除：id={}", id);
            }
        } catch (Exception e) {
            log.warn("Milvus 向量删除异常：{}", e.getMessage());
        }
    }

    /**
     * 懒加载确保集合与索引已创建
     */
    private synchronized void ensureCollection(MilvusServiceClient client) {
        if (collectionEnsured) {
            return;
        }
        try {
            R<Boolean> hasCollection = client.hasCollection(
                    HasCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build());

            if (hasCollection.getStatus() == 0 && Boolean.FALSE.equals(hasCollection.getData())) {
                createCollection(client);
                createIndex(client);
            } else if (hasCollection.getStatus() == 0 && Boolean.TRUE.equals(hasCollection.getData())) {
                log.info("Milvus 集合已存在：{}", collectionName);
            }
            collectionEnsured = true;
        } catch (Exception e) {
            log.warn("Milvus 集合初始化失败：{}", e.getMessage());
        }
    }

    /**
     * 创建集合（id 主键 + FloatVector 向量字段）
     */
    private void createCollection(MilvusServiceClient client) {
        FieldType idField = FieldType.newBuilder()
                .withName(ID_FIELD)
                .withDataType(DataType.VarChar)
                .withMaxLength(128)
                .withPrimaryKey(true)
                .withAutoID(false)
                .build();

        FieldType vectorField = FieldType.newBuilder()
                .withName(VECTOR_FIELD)
                .withDataType(DataType.FloatVector)
                .withDimension(dimension)
                .build();

        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldTypes(new ArrayList<>(List.of(idField, vectorField)))
                .withDescription("RAG 知识库向量集合")
                .build();

        R<RpcStatus> resp = client.createCollection(createParam);
        if (resp.getStatus() != 0) {
            log.warn("Milvus 集合创建失败：{}", resp.getMessage());
        } else {
            log.info("Milvus 集合已创建：{}", collectionName);
        }
    }

    /**
     * 为向量字段构建 HNSW 索引
     */
    private void createIndex(MilvusServiceClient client) {
        CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName(VECTOR_FIELD)
                .withIndexType(resolveIndexType())
                .withMetricType(resolveMetricType())
                .withExtraParam(resolveIndexParams())
                .build();

        R<RpcStatus> resp = client.createIndex(indexParam);
        if (resp.getStatus() != 0) {
            log.warn("Milvus 索引创建失败：{}", resp.getMessage());
        } else {
            log.info("Milvus 索引已创建：{} ({})", collectionName, indexType);
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

    /** HNSW 索引构建参数 */
    private String resolveIndexParams() {
        return resolveIndexType() == IndexType.HNSW
                ? "{\"M\": 16, \"efConstruction\": 200}"
                : "{}";
    }

    /** ANN 检索参数（HNSW 的 ef 值） */
    private String resolveSearchParams() {
        return resolveIndexType() == IndexType.HNSW
                ? "{\"ef\": 64}"
                : "{}";
    }

    private MilvusServiceClient client() {
        return clientProvider.getIfAvailable();
    }

    /**
     * 检索命中结果
     */
    public static class SearchHit {
        private final String id;
        private final float score;

        public SearchHit(String id, float score) {
            this.id = id;
            this.score = score;
        }

        public String getId() {
            return id;
        }

        public float getScore() {
            return score;
        }
    }
}
