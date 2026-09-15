package com.ai.rag.model.dto;

import com.ai.rag.service.evaluation.RetrievalMetrics;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 检索评测报告（Stage 4）
 *
 * @param dataset       数据集名称
 * @param topK          本次评测取前 K 条计算指标
 * @param knowledgeBaseId 检索范围（null 为全库）
 * @param caseCount     用例数
 * @param aggregate     汇总指标
 * @param cases         逐用例明细
 * @param notes         运行提示（如「向量路全部为空，可能缺少可用的 Embedding API Key」）
 * @param startedAt     评测开始时间
 */
public record EvaluationReport(String dataset,
                               int topK,
                               Long knowledgeBaseId,
                               int caseCount,
                               RetrievalMetrics.Aggregate aggregate,
                               List<RetrievalMetrics.CaseResult> cases,
                               List<String> notes,
                               LocalDateTime startedAt) {

    public EvaluationReport {
        cases = cases == null ? List.of() : List.copyOf(cases);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
