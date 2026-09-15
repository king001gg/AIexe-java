package com.ai.rag.service.evaluation;

import com.ai.rag.model.entity.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索相关性判定测试
 */
class RelevanceMatcherTest {

    private static Document document(String chunkId, String title, String content) {
        Document document = new Document();
        document.setChunkId(chunkId);
        document.setTitle(title);
        document.setContent(content);
        return document;
    }

    @Test
    @DisplayName("支持 chunkId 精确匹配")
    void matchesByChunkId() {
        Document doc = document("chunk-001", "标题", "正文");

        assertThat(RelevanceMatcher.matches(doc, "chunk-001")).isTrue();
        assertThat(RelevanceMatcher.matches(doc, "chunk-002")).isFalse();
    }

    @Test
    @DisplayName("支持标题/正文关键词子串匹配，且忽略大小写")
    void matchesByKeywordIgnoringCase() {
        Document doc = document("c1", "多路召回策略", "使用 RRF 融合向量与关键词两路结果");

        assertThat(RelevanceMatcher.matches(doc, "多路召回")).isTrue();
        assertThat(RelevanceMatcher.matches(doc, "rrf")).isTrue();
        assertThat(RelevanceMatcher.matches(doc, "向量与关键词")).isTrue();
        assertThat(RelevanceMatcher.matches(doc, "不存在的词")).isFalse();
    }

    @Test
    @DisplayName("空条目 / 空文档不视为命中")
    void handlesNullsAndBlanks() {
        Document doc = document("c1", null, null);

        assertThat(RelevanceMatcher.matches(doc, null)).isFalse();
        assertThat(RelevanceMatcher.matches(doc, "  ")).isFalse();
        assertThat(RelevanceMatcher.matches(null, "c1")).isFalse();
    }

    @Test
    @DisplayName("按排名顺序标记相关性")
    void marksRelevanceByRank() {
        List<Document> ranked = List.of(
                document("c1", "无关", "无关内容"),
                document("c2", "RRF 融合", "倒数排名融合"),
                document("c3", "无关", "无关内容"));

        assertThat(RelevanceMatcher.relevanceByRank(ranked, List.of("RRF")))
                .containsExactly(false, true, false);
    }

    @Test
    @DisplayName("找出未被满足的 ground truth 条目")
    void findsMissingEntries() {
        List<Document> ranked = List.of(document("c1", "Milvus 索引", "使用 HNSW"));

        assertThat(RelevanceMatcher.findMissing(ranked, List.of("Milvus", "HNSW", "不存在的词")))
                .containsExactly("不存在的词");
        assertThat(RelevanceMatcher.findMissing(List.of(), List.of("Milvus"))).containsExactly("Milvus");
    }
}
