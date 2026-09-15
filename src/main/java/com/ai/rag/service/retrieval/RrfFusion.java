package com.ai.rag.service.retrieval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Reciprocal Rank Fusion（RRF，倒数排名融合）
 *
 * <p>把多路召回（如向量语义召回、关键词稀疏召回）的**排名列表**融合为单一排名：
 * <pre>
 *     score(d) = Σ<sub>每一路 i</sub> 1 / (k + rank<sub>i</sub>(d))
 * </pre>
 * 其中 rank 从 1 开始计数；若文档 d 未出现在第 i 路结果中，则该路不贡献分数。
 * k 为平滑常数（默认 {@value #DEFAULT_K}，取自 Cormack et al. 2009），用于削弱头部排名的绝对优势，
 * 使单路第一名的权重不会压倒另一路的前几名。
 *
 * <p>RRF 只使用「排名」而不使用「分数」，因此天然免疫不同召回路的分数尺度差异
 * （余弦相似度 ∈ [-1,1] 与 BM25/LIKE 命中次数无法直接加权相加）。
 *
 * <p>本类为纯函数实现，不依赖 Spring / 数据库，便于单元测试。
 */
public final class RrfFusion {

    /** 默认平滑常数 */
    public static final int DEFAULT_K = 60;

    private RrfFusion() {
    }

    /**
     * 融合结果
     *
     * @param item  命中的元素
     * @param score RRF 融合分（越高越相关）
     * @param ranks 在每一路召回中的 1-based 排名（下标与传入的 rankedLists 一一对应）；0 表示该路未召回
     */
    public record Fused<T>(T item, double score, List<Integer> ranks) {
    }

    /**
     * 融合多路召回结果
     *
     * @param rankedLists 多路召回结果，每路内部须已按相关性**降序**排列
     * @param k           RRF 平滑常数（&lt;=0 时回退为 {@link #DEFAULT_K}）
     * @param topN        最终返回条数
     * @param idExtractor 元素唯一标识提取函数，用于跨路去重；返回 null 的元素会被跳过
     * @return 按融合分降序排列的结果；分数相同时保持首次命中的先后顺序
     */
    public static <T> List<Fused<T>> fuse(List<List<T>> rankedLists,
                                          int k,
                                          int topN,
                                          Function<T, ?> idExtractor) {
        if (rankedLists == null || rankedLists.isEmpty() || topN <= 0) {
            return List.of();
        }

        int effectiveK = k > 0 ? k : DEFAULT_K;
        int routeCount = rankedLists.size();
        Map<Object, Accumulator<T>> accumulators = new LinkedHashMap<>();

        for (int route = 0; route < routeCount; route++) {
            List<T> rankedList = rankedLists.get(route);
            if (rankedList == null) {
                continue;
            }
            for (int position = 0; position < rankedList.size(); position++) {
                T item = rankedList.get(position);
                if (item == null) {
                    continue;
                }
                Object id = idExtractor.apply(item);
                if (id == null) {
                    continue;
                }

                Accumulator<T> accumulator = accumulators.computeIfAbsent(id,
                        key -> new Accumulator<>(item, new ArrayList<>(Collections.nCopies(routeCount, 0))));
                accumulator.ranks.set(route, position + 1);
                accumulator.score += 1.0 / (effectiveK + position + 1);
            }
        }

        return accumulators.values().stream()
                .sorted(Comparator.comparingDouble(Accumulator<T>::score).reversed())
                .limit(topN)
                .map(a -> new Fused<>(a.item, a.score, List.copyOf(a.ranks)))
                .toList();
    }

    /**
     * 累加器（融合过程中的可变中间态）
     */
    private static final class Accumulator<T> {
        private final T item;
        private final List<Integer> ranks;
        private double score;

        private Accumulator(T item, List<Integer> ranks) {
            this.item = item;
            this.ranks = ranks;
        }

        private double score() {
            return score;
        }
    }
}
