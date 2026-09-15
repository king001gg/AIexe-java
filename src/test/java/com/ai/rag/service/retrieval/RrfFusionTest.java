package com.ai.rag.service.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * RRF 融合单元测试
 */
class RrfFusionTest {

    private static final int K = 60;

    @Test
    @DisplayName("单路召回：分数为 1/(k+rank)，排名顺序保持不变")
    void singleRouteKeepsOrderAndScores() {
        List<RrfFusion.Fused<String>> fused = RrfFusion.fuse(
                List.of(List.of("a", "b", "c")), K, 10, s -> s);

        assertThat(fused).extracting(RrfFusion.Fused::item).containsExactly("a", "b", "c");
        assertThat(fused.get(0).score()).isCloseTo(1.0 / (K + 1), within(1e-9));
        assertThat(fused.get(1).score()).isCloseTo(1.0 / (K + 2), within(1e-9));
        assertThat(fused.get(2).score()).isCloseTo(1.0 / (K + 3), within(1e-9));
    }

    @Test
    @DisplayName("两路都命中的元素排名高于只被一路命中的元素")
    void itemInBothRoutesOutranksSingleRouteItem() {
        List<RrfFusion.Fused<String>> fused = RrfFusion.fuse(
                List.of(List.of("d1", "d2"), List.of("d2", "d3")), K, 10, s -> s);

        // d2 在向量路 rank2 + 关键词路 rank1 = 1/62 + 1/61，超过 d1 仅有的 1/61
        assertThat(fused).extracting(RrfFusion.Fused::item).containsExactly("d2", "d1", "d3");
        assertThat(fused.get(0).score()).isCloseTo(1.0 / (K + 2) + 1.0 / (K + 1), within(1e-9));
    }

    @Test
    @DisplayName("同一元素跨路出现只保留一条，且记录各路排名")
    void deduplicatesAcrossRoutesAndRecordsRanks() {
        List<RrfFusion.Fused<String>> fused = RrfFusion.fuse(
                List.of(List.of("x", "y"), List.of("y", "x")), K, 10, s -> s);

        assertThat(fused).hasSize(2);
        // x: 向量 rank1 + 关键词 rank2；y: 向量 rank2 + 关键词 rank1 —— 分数相同，按首次命中顺序
        assertThat(fused.get(0).item()).isEqualTo("x");
        assertThat(fused.get(0).ranks()).containsExactly(1, 2);
        assertThat(fused.get(1).item()).isEqualTo("y");
        assertThat(fused.get(1).ranks()).containsExactly(2, 1);
    }

    @Test
    @DisplayName("未在某一路出现的元素，该路排名记为 0")
    void absentRouteRankIsZero() {
        List<RrfFusion.Fused<String>> fused = RrfFusion.fuse(
                List.of(List.of("onlyVector"), List.of("onlyKeyword")), K, 10, s -> s);

        assertThat(fused.get(0).ranks()).containsExactly(1, 0);
        assertThat(fused.get(1).ranks()).containsExactly(0, 1);
    }

    @Test
    @DisplayName("结果条数受 topN 限制")
    void respectsTopN() {
        List<RrfFusion.Fused<String>> fused = RrfFusion.fuse(
                List.of(List.of("a", "b", "c", "d")), K, 2, s -> s);

        assertThat(fused).hasSize(2);
        assertThat(fused).extracting(RrfFusion.Fused::item).containsExactly("a", "b");
    }

    @Test
    @DisplayName("空输入与非法 topN 返回空结果")
    void handlesEmptyInput() {
        assertThat(RrfFusion.fuse(null, K, 5, s -> s)).isEmpty();
        assertThat(RrfFusion.fuse(List.of(), K, 5, s -> s)).isEmpty();
        assertThat(RrfFusion.fuse(List.of(List.of("a")), K, 0, s -> s)).isEmpty();
        assertThat(RrfFusion.fuse(List.of(List.of()), K, 5, s -> s)).isEmpty();
    }

    @Test
    @DisplayName("一路为空时退化为另一路的排名")
    void degradesToSingleRouteWhenOneIsEmpty() {
        List<RrfFusion.Fused<String>> fused = RrfFusion.fuse(
                List.of(List.of("a", "b"), List.of()), K, 10, s -> s);

        assertThat(fused).extracting(RrfFusion.Fused::item).containsExactly("a", "b");
        assertThat(fused.get(0).ranks()).containsExactly(1, 0);
    }

    @Test
    @DisplayName("标识为 null 的元素被跳过，null 路由被忽略")
    void skipsNullIdsAndNullRoutes() {
        List<RrfFusion.Fused<String>> fused = RrfFusion.fuse(
                List.of(List.of("a"), List.of()), K, 10, s -> "a".equals(s) ? "a" : null);

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).item()).isEqualTo("a");
    }

    @Test
    @DisplayName("k <= 0 时回退为默认平滑常数")
    void fallsBackToDefaultK() {
        List<RrfFusion.Fused<String>> fused = RrfFusion.fuse(
                List.of(List.of("a")), 0, 10, s -> s);

        assertThat(fused.get(0).score())
                .isCloseTo(1.0 / (RrfFusion.DEFAULT_K + 1), within(1e-9));
    }
}
