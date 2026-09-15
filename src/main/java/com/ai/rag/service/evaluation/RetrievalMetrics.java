package com.ai.rag.service.evaluation;

import java.util.List;

/**
 * 检索质量指标（Stage 4）
 *
 * <p>纯函数实现，不依赖 Spring / 数据库，便于单测。
 *
 * <p>相关性判定只有一种口径：一个 ground truth 条目（chunkId 或关键词）被某个分块满足，
 * 就认为该分块是「相关的」。一条用例的 ground truth 可能被同一个分块满足多项，
 * 因此召回率按 **条目** 计，精确率/MRR 按 **分块** 计。
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /**
     * 单条用例的评测结果
     *
     * @param caseId            用例标识
     * @param question          查询问题
     * @param retrievedCount    实际召回条数
     * @param expectedCount     ground truth 条目总数
     * @param satisfiedCount    被 top-K 结果满足的 ground truth 条目数
     * @param firstRelevantRank 首个相关分块的排名（1-based），0 表示 top-K 内无相关结果
     * @param recall            satisfiedCount / expectedCount
     * @param precision         相关分块数 / retrievedCount
     * @param reciprocalRank    1 / firstRelevantRank，无相关结果时为 0
     * @param hit               是否至少命中一个相关分块
     * @param missing           未被满足的 ground truth 条目（便于定位漏召）
     */
    public record CaseResult(String caseId,
                             String question,
                             int retrievedCount,
                             int expectedCount,
                             int satisfiedCount,
                             int firstRelevantRank,
                             double recall,
                             double precision,
                             double reciprocalRank,
                             boolean hit,
                             List<String> missing) {
    }

    /**
     * 数据集汇总指标（对用例取算术平均）
     *
     * @param caseCount    用例数
     * @param recallAtK    平均召回率
     * @param precisionAtK 平均精确率
     * @param mrr          平均倒数排名（Mean Reciprocal Rank）
     * @param hitRate      命中率（Hit@K，至少命中一条相关结果的用例占比）
     */
    public record Aggregate(int caseCount,
                            double recallAtK,
                            double precisionAtK,
                            double mrr,
                            double hitRate) {
    }

    /**
     * 计算单条用例的指标
     *
     * @param relevanceByRank 按排名（1-based 顺序）排列的相关性标记，true 表示该分块满足至少一个 ground truth 条目
     * @param missing         未被满足的 ground truth 条目
     * @param expectedCount   ground truth 条目总数
     */
    public static CaseResult evaluateCase(String caseId,
                                          String question,
                                          List<Boolean> relevanceByRank,
                                          List<String> missing,
                                          int expectedCount) {
        List<Boolean> relevance = relevanceByRank == null ? List.of() : relevanceByRank;
        List<String> missingEntries = missing == null ? List.of() : List.copyOf(missing);

        int retrievedCount = relevance.size();
        long relevantRetrieved = relevance.stream().filter(Boolean::booleanValue).count();
        int satisfiedCount = Math.max(expectedCount - missingEntries.size(), 0);

        int firstRelevantRank = 0;
        for (int i = 0; i < relevance.size(); i++) {
            if (Boolean.TRUE.equals(relevance.get(i))) {
                firstRelevantRank = i + 1;
                break;
            }
        }

        double recall = expectedCount <= 0 ? 0.0 : (double) satisfiedCount / expectedCount;
        double precision = retrievedCount == 0 ? 0.0 : (double) relevantRetrieved / retrievedCount;
        double reciprocalRank = firstRelevantRank == 0 ? 0.0 : 1.0 / firstRelevantRank;

        return new CaseResult(caseId, question, retrievedCount, expectedCount, satisfiedCount,
                firstRelevantRank, recall, precision, reciprocalRank,
                relevantRetrieved > 0, missingEntries);
    }

    /**
     * 汇总多条用例的指标（算术平均；空列表返回全 0）
     */
    public static Aggregate aggregate(List<CaseResult> results) {
        if (results == null || results.isEmpty()) {
            return new Aggregate(0, 0.0, 0.0, 0.0, 0.0);
        }
        double recallSum = 0.0;
        double precisionSum = 0.0;
        double reciprocalRankSum = 0.0;
        int hitCount = 0;

        for (CaseResult result : results) {
            recallSum += result.recall();
            precisionSum += result.precision();
            reciprocalRankSum += result.reciprocalRank();
            if (result.hit()) {
                hitCount++;
            }
        }

        int caseCount = results.size();
        return new Aggregate(
                caseCount,
                recallSum / caseCount,
                precisionSum / caseCount,
                reciprocalRankSum / caseCount,
                (double) hitCount / caseCount);
    }
}
