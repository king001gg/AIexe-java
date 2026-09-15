package com.ai.rag.service.evaluation;

import com.ai.rag.model.dto.EvaluationCase;
import com.ai.rag.model.dto.EvaluationDataset;
import com.ai.rag.model.dto.EvaluationReport;
import com.ai.rag.model.dto.RetrievalHit;
import com.ai.rag.model.entity.Document;
import com.ai.rag.service.RagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 检索质量评测（Stage 4）
 *
 * <p>读取评测数据集，对每条问题跑一次 Stage 2 的多路召回 + RRF 融合，
 * 再用 {@link RetrievalMetrics} 计算 Recall@K / Precision@K / MRR / HitRate。
 *
 * <p>只评测**检索**，不评测生成质量：生成质量需要 LLM 裁判，既依赖可用的 API Key，
 * 结果也带有裁判模型自身的偏好，不适合作为回归基线。检索指标是确定性的，可稳定比对。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalEvaluator {

    private final RagService ragService;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    /** 默认评测数据集位置（支持 classpath: 前缀） */
    @Value("${rag.evaluation.dataset:classpath:evaluation/retrieval-cases.json}")
    private String defaultDatasetLocation;

    /** 数据集未指定 K 时的默认值 */
    @Value("${rag.retrieval.final-top-k:5}")
    private int defaultTopK;

    /**
     * 运行评测
     *
     * @param datasetLocation     数据集位置，为空则用 {@code rag.evaluation.dataset}
     * @param knowledgeBaseIdOverride 覆盖数据集中限定的知识库（null 表示沿用数据集配置）
     * @param topKOverride        覆盖数据集中的 K（null 表示沿用数据集配置）
     */
    public EvaluationReport evaluate(String datasetLocation, Long knowledgeBaseIdOverride, Integer topKOverride) {
        EvaluationDataset dataset = loadDataset(datasetLocation);

        Long knowledgeBaseId = knowledgeBaseIdOverride != null ? knowledgeBaseIdOverride : dataset.knowledgeBaseId();
        int topK = topKOverride != null ? topKOverride
                : (dataset.topK() != null ? dataset.topK() : defaultTopK);

        LocalDateTime startedAt = LocalDateTime.now();
        List<RetrievalMetrics.CaseResult> caseResults = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int totalRetrieved = 0;

        for (EvaluationCase evaluationCase : dataset.cases()) {
            if (evaluationCase.question() == null || evaluationCase.question().isBlank()) {
                notes.add("用例 " + evaluationCase.id() + " 缺少 question，已跳过");
                continue;
            }
            if (evaluationCase.expected().isEmpty()) {
                notes.add("用例 " + evaluationCase.id() + " 未配置 expected，召回率恒为 0");
            }

            List<Document> ranked;
            try {
                ranked = ragService.searchWithFusion(evaluationCase.question(), knowledgeBaseId, topK).stream()
                        .map(RetrievalHit::document)
                        .toList();
            } catch (RuntimeException e) {
                // 单条用例失败不应中断整轮评测，记为未召回并留痕
                log.warn("评测用例 {} 检索失败：{}", evaluationCase.id(), e.getMessage());
                notes.add("用例 " + evaluationCase.id() + " 检索异常：" + e.getMessage());
                ranked = List.of();
            }

            totalRetrieved += ranked.size();
            caseResults.add(RetrievalMetrics.evaluateCase(
                    evaluationCase.id(),
                    evaluationCase.question(),
                    RelevanceMatcher.relevanceByRank(ranked, evaluationCase.expected()),
                    RelevanceMatcher.findMissing(ranked, evaluationCase.expected()),
                    evaluationCase.expected().size()));
        }

        if (!caseResults.isEmpty() && totalRetrieved == 0) {
            notes.add("所有用例均未召回任何分块：请确认知识库已导入数据，且 Embedding 服务可用"
                    + "（无有效 API Key 时向量路会降级为空，关键词路仍应命中）");
        }

        RetrievalMetrics.Aggregate aggregate = RetrievalMetrics.aggregate(caseResults);
        log.info("检索评测完成：dataset={}, cases={}, recall@K={}, mrr={}, hitRate={}",
                dataset.name(), aggregate.caseCount(),
                String.format("%.3f", aggregate.recallAtK()),
                String.format("%.3f", aggregate.mrr()),
                String.format("%.3f", aggregate.hitRate()));

        return new EvaluationReport(dataset.name(), topK, knowledgeBaseId, caseResults.size(),
                aggregate, caseResults, notes, startedAt);
    }

    /**
     * 读取数据集（支持 {@code classpath:} 前缀与文件路径）
     *
     * @param location 为空时使用 {@code rag.evaluation.dataset} 配置的默认位置
     */
    public EvaluationDataset loadDataset(String location) {
        String resolvedLocation = resolveLocation(location);
        Resource resource = resourceLoader.getResource(resolvedLocation);
        if (!resource.exists()) {
            throw new IllegalArgumentException("评测数据集不存在：" + resolvedLocation);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, EvaluationDataset.class);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "评测数据集解析失败：" + resolvedLocation + " —— " + e.getMessage(), e);
        }
    }

    /**
     * 数据集位置为空时回退到配置的默认值
     */
    private String resolveLocation(String location) {
        return (location == null || location.isBlank()) ? defaultDatasetLocation : location;
    }
}
