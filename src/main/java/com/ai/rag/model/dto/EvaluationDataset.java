package com.ai.rag.model.dto;

import java.util.List;

/**
 * 检索评测数据集（Stage 4）
 *
 * @param name            数据集名称
 * @param description     说明
 * @param knowledgeBaseId 限定检索的知识库 ID，{@code null} 表示全库检索
 * @param topK            该数据集建议的 K（截断到前 K 条计算指标），{@code null} 表示用全局默认
 * @param cases           用例列表
 */
public record EvaluationDataset(String name,
                                String description,
                                Long knowledgeBaseId,
                                Integer topK,
                                List<EvaluationCase> cases) {

    public EvaluationDataset {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
