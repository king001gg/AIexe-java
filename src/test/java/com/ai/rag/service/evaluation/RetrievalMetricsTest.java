package com.ai.rag.service.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索指标计算测试
 */
class RetrievalMetricsTest {

    @Test
    @DisplayName("首个结果即相关：recall / precision / MRR 均为 1.0")
    void perfectTopOneHit() {
        RetrievalMetrics.CaseResult result = RetrievalMetrics.evaluateCase(
                "q1", "问题", List.of(true, false, false), List.of(), 1);

        assertThat(result.retrievedCount()).isEqualTo(3);
        assertThat(result.firstRelevantRank()).isEqualTo(1);
        assertThat(result.recall()).isEqualTo(1.0);
        assertThat(result.precision()).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(result.reciprocalRank()).isEqualTo(1.0);
        assertThat(result.hit()).isTrue();
    }

    @Test
    @DisplayName("排在第 2 位：MRR 为 1/2，精确率按实际召回条数计")
    void hitAtSecondPosition() {
        RetrievalMetrics.CaseResult result = RetrievalMetrics.evaluateCase(
                "q2", "问题", List.of(false, true), List.of("缺失项"), 2);

        assertThat(result.firstRelevantRank()).isEqualTo(2);
        assertThat(result.reciprocalRank()).isEqualTo(0.5);
        assertThat(result.satisfiedCount()).isEqualTo(1);
        assertThat(result.recall()).isEqualTo(0.5);
        assertThat(result.precision()).isEqualTo(0.5);
        assertThat(result.missing()).containsExactly("缺失项");
    }

    @Test
    @DisplayName("完全未召回：各项指标为 0 且 hit=false")
    void noHits() {
        RetrievalMetrics.CaseResult result = RetrievalMetrics.evaluateCase(
                "q3", "问题", List.of(false, false), List.of("a", "b"), 2);

        assertThat(result.firstRelevantRank()).isZero();
        assertThat(result.recall()).isZero();
        assertThat(result.precision()).isZero();
        assertThat(result.reciprocalRank()).isZero();
        assertThat(result.hit()).isFalse();
    }

    @Test
    @DisplayName("空结果集不抛异常（除零保护）")
    void handlesEmptyInputs() {
        RetrievalMetrics.CaseResult result = RetrievalMetrics.evaluateCase("q4", "问题", List.of(), List.of(), 0);

        assertThat(result.retrievedCount()).isZero();
        assertThat(result.recall()).isZero();
        assertThat(result.precision()).isZero();
        assertThat(result.hit()).isFalse();
    }

    @Test
    @DisplayName("汇总：对用例取算术平均，HitRate 为命中用例占比")
    void aggregatesAcrossCases() {
        RetrievalMetrics.CaseResult hitFirst = RetrievalMetrics.evaluateCase(
                "a", "q", List.of(true), List.of(), 1);           // recall 1, rr 1, hit
        RetrievalMetrics.CaseResult hitSecond = RetrievalMetrics.evaluateCase(
                "b", "q", List.of(false, true), List.of(), 1);    // recall 1, rr 0.5, hit
        RetrievalMetrics.CaseResult miss = RetrievalMetrics.evaluateCase(
                "c", "q", List.of(false), List.of("x"), 1);       // recall 0, rr 0, miss

        RetrievalMetrics.Aggregate aggregate = RetrievalMetrics.aggregate(List.of(hitFirst, hitSecond, miss));

        assertThat(aggregate.caseCount()).isEqualTo(3);
        assertThat(aggregate.recallAtK()).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(aggregate.mrr()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(aggregate.hitRate()).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("空用例列表汇总为全 0")
    void aggregatesEmptyList() {
        RetrievalMetrics.Aggregate aggregate = RetrievalMetrics.aggregate(List.of());

        assertThat(aggregate.caseCount()).isZero();
        assertThat(aggregate.recallAtK()).isZero();
        assertThat(aggregate.mrr()).isZero();
        assertThat(aggregate.hitRate()).isZero();
    }
}
