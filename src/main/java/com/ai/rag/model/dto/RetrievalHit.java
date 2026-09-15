package com.ai.rag.model.dto;

import com.ai.rag.model.entity.Document;

/**
 * 多路召回融合后的检索命中结果（Stage 2）
 *
 * @param document    命中的文档分块
 * @param score       RRF 融合分（越高越相关）
 * @param vectorRank  在向量语义召回中的 1-based 排名，0 表示该路未召回
 * @param keywordRank 在关键词稀疏召回中的 1-based 排名，0 表示该路未召回
 */
public record RetrievalHit(Document document, double score, int vectorRank, int keywordRank) {

    /** 是否被向量路召回 */
    public boolean recalledByVector() {
        return vectorRank > 0;
    }

    /** 是否被关键词路召回 */
    public boolean recalledByKeyword() {
        return keywordRank > 0;
    }

    /** 召回来源：hybrid / vector / keyword */
    public String source() {
        if (recalledByVector() && recalledByKeyword()) {
            return "hybrid";
        }
        return recalledByVector() ? "vector" : "keyword";
    }
}
