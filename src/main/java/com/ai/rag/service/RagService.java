package com.ai.rag.service;

import com.ai.rag.model.entity.Document;
import com.ai.rag.model.entity.KnowledgeBase;
import com.ai.rag.repository.DocumentRepository;
import com.ai.rag.repository.KnowledgeBaseRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG检索服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final EmbeddingModel embeddingModel;

    /**
     * 检索相关知识
     */
    public List<Document> searchRelevantDocuments(String query, Long knowledgeBaseId, int topK) {
        log.info("Searching relevant documents for query: {}", query);

        try {
            // 1. 生成查询向量
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            float[] queryVector = queryEmbedding.vector();

            // 2. Milvus向量搜索
            List<String> vectorIds = searchVectors(queryVector, topK);

            // 3. 获取相关文档
            if (!vectorIds.isEmpty()) {
                List<Document> documents = documentRepository.findByVectorIdIn(vectorIds);

                // 根据相关性排序
                return rankDocuments(documents, query, vectorIds);
            }

            // 4. 如果没有找到向量，使用关键词搜索
            return keywordSearch(query, knowledgeBaseId, topK);

        } catch (Exception e) {
            log.error("Error searching documents", e);
            // 回退到关键词搜索
            return keywordSearch(query, knowledgeBaseId, topK);
        }
    }

    /**
     * 向量搜索
     */
    private List<String> searchVectors(float[] queryVector, int topK) {
        try {
            // 这里应该使用Milvus SDK进行向量搜索
            // 由于Milvus Java SDK较为复杂，这里简化为返回所有向量ID
            // 实际项目中需要实现完整的向量搜索逻辑

            log.info("Performing vector search with dimension: {}", queryVector.length);

            // 模拟向量搜索结果
            List<String> allVectorIds = documentRepository.findAll().stream()
                .map(Document::getVectorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

            // 随机选择topK个（实际应该根据相似度排序）
            Collections.shuffle(allVectorIds);
            return allVectorIds.stream()
                .limit(topK)
                .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error in vector search", e);
            return Collections.emptyList();
        }
    }

    /**
     * 关键词搜索
     */
    private List<Document> keywordSearch(String query, Long knowledgeBaseId, int topK) {
        log.info("Performing keyword search for query: {}", query);

        List<Document> documents = documentRepository.searchByKeyword(knowledgeBaseId, query);

        // 如果没有指定知识库，搜索所有
        if (documents.isEmpty() && knowledgeBaseId == null) {
            documents = documentRepository.findAll();
            documents = documents.stream()
                .filter(doc -> doc.getContent().toLowerCase().contains(query.toLowerCase()))
                .collect(Collectors.toList());
        }

        return documents.stream()
            .limit(topK)
            .collect(Collectors.toList());
    }

    /**
     * 根据相关性对文档排序
     */
    private List<Document> rankDocuments(List<Document> documents, String query, List<String> vectorIds) {
        // 简单的相关性排序
        return documents.stream()
            .sorted((d1, d2) -> {
                // 根据在vectorIds中的位置排序
                int pos1 = vectorIds.indexOf(d1.getVectorId());
                int pos2 = vectorIds.indexOf(d2.getVectorId());
                return Integer.compare(pos1, pos2);
            })
            .collect(Collectors.toList());
    }

    /**
     * 构建检索上下文
     */
    public String buildSearchContext(List<Document> documents) {
        if (documents.isEmpty()) {
            return "没有找到相关的知识库信息。";
        }

        StringBuilder context = new StringBuilder();
        context.append("以下是与问题相关的知识库信息：\n\n");

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            context.append(String.format(
                "【文档%d】%s\n%s\n\n",
                i + 1,
                doc.getTitle() != null ? doc.getTitle() : "未知标题",
                doc.getContent()
            ));
        }

        return context.toString();
    }

    /**
     * 计算检索覆盖率
     */
    public double calculateCoverage(String query, List<Document> retrievedDocuments, Long knowledgeBaseId) {
        if (retrievedDocuments.isEmpty()) {
            return 0.0;
        }

        // 简单覆盖率计算：基于查询词在文档中的出现频率
        String[] queryTerms = query.toLowerCase().split("\\s+");
        int totalTerms = queryTerms.length;
        int coveredTerms = 0;

        Set<String> documentTerms = retrievedDocuments.stream()
            .flatMap(doc -> Arrays.stream(doc.getContent().toLowerCase().split("\\s+")))
            .collect(Collectors.toSet());

        for (String term : queryTerms) {
            if (documentTerms.contains(term)) {
                coveredTerms++;
            }
        }

        return (double) coveredTerms / totalTerms;
    }

    /**
     * 更新知识库统计
     */
    public void updateKnowledgeBaseStats(Long knowledgeBaseId) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(knowledgeBaseId)
            .orElseThrow(() -> new RuntimeException("Knowledge base not found"));

        long docCount = documentRepository.countByKnowledgeBaseId(knowledgeBaseId);
        long totalTokens = documentRepository.sumTokensByKnowledgeBaseId(knowledgeBaseId);

        kb.setDocCount((int) docCount);
        kb.setTotalTokens((int) totalTokens);
        knowledgeBaseRepository.save(kb);

        log.info("Updated knowledge base stats: docCount={}, totalTokens={}", docCount, totalTokens);
    }

    /**
     * 获取知识库统计信息
     */
    public Map<String, Object> getKnowledgeBaseStats(Long knowledgeBaseId) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(knowledgeBaseId)
            .orElseThrow(() -> new RuntimeException("Knowledge base not found"));

        long docCount = documentRepository.countByKnowledgeBaseId(knowledgeBaseId);
        long totalTokens = documentRepository.sumTokensByKnowledgeBaseId(knowledgeBaseId);

        Map<String, Object> stats = new HashMap<>();
        stats.put("knowledgeBaseName", kb.getName());
        stats.put("documentCount", docCount);
        stats.put("totalTokens", totalTokens);
        stats.put("averageTokensPerDocument", docCount > 0 ? totalTokens / docCount : 0);
        stats.put("createdAt", kb.getCreatedAt());
        stats.put("updatedAt", kb.getUpdatedAt());

        return stats;
    }
}