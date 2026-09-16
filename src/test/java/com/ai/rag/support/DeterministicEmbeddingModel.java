package com.ai.rag.support;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性的 {@link EmbeddingModel} 测试桩（词袋哈希）
 *
 * <p>动机：真实 embedding 需要 API Key，导致测试里向量召回永远为空，
 * 多路召回/RRF 的融合结果无法在集成测试中验证。此桩把文本映射为
 * 「共享词元越多、余弦相似度越高」的向量，使向量检索在测试中**语义上真实可用**：
 * 同一段文本必然得到同一向量，包含查询词的文档会被真正召回。
 *
 * <p>实现要点：
 * <ul>
 *   <li>英文按词切分；中文按 **相邻双字（2-gram）** 切分，与 {@code RagService}
 *       关键词扩展的口径基本一致，保证中英文查询都能命中</li>
 *   <li>子线性词频（1 + ln tf）+ L2 归一化，使点积即为余弦相似度</li>
 *   <li>{@link String#hashCode()} 在同一 JVM 版本内稳定，因此结果是确定性的</li>
 * </ul>
 *
 * <p>注意：本桩**不具备真实语义泛化能力**（「汽车」与「轿车」不会接近），
 * 因此不能用于评测 embedding 质量；它只用于验证检索链路的接线与融合逻辑是否正确。
 */
public class DeterministicEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSION = 1536;
    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9]+");
    private static final int CJK_START = 0x4E00;
    private static final int CJK_END = 0x9FFF;

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        List<Embedding> embeddings = new ArrayList<>(segments.size());
        for (TextSegment segment : segments) {
            embeddings.add(embedText(segment.text()));
        }
        return Response.from(embeddings);
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    /**
     * 文本 -> L2 归一化的词袋哈希向量
     *
     * <p>命名为 {@code embedText} 而非 {@code embed}：{@code EmbeddingModel} 自带的
     * {@code embed(String)} 返回的是 {@code Response<Embedding>}，同名会造成返回类型冲突。
     */
    public Embedding embedText(String text) {
        float[] vector = new float[DIMENSION];
        for (Map.Entry<String, Integer> entry : termFrequencies(text).entrySet()) {
            int bucket = Math.floorMod(entry.getKey().hashCode(), DIMENSION);
            vector[bucket] += (float) (1.0 + Math.log(entry.getValue()));
        }
        normalize(vector);
        return Embedding.from(vector);
    }

    /**
     * 词元频率统计
     */
    static Map<String, Integer> termFrequencies(String text) {
        Map<String, Integer> frequencies = new HashMap<>();
        if (text == null || text.isBlank()) {
            return frequencies;
        }
        String lower = text.toLowerCase(Locale.ROOT);

        Matcher matcher = ASCII_WORD.matcher(lower);
        while (matcher.find()) {
            frequencies.merge(matcher.group(), 1, Integer::sum);
        }

        // 中文只取相邻双字（2-gram），刻意不取单字：
        // 单字（的/是/了…）几乎在所有中文文本里都出现，会让任意两段中文的余弦相似度都大于 0，
        // 「无关问题不召回」这类断言就无法成立。2-gram 才具备区分度。
        // 单个孤立的汉字（前后都不构成 2-gram）仍保留单字，否则单字查询会完全没有向量。
        List<Character> cjk = new ArrayList<>();
        for (char c : lower.toCharArray()) {
            if (c >= CJK_START && c <= CJK_END) {
                cjk.add(c);
            }
        }
        for (int i = 0; i + 1 < cjk.size(); i++) {
            frequencies.merge("" + cjk.get(i) + cjk.get(i + 1), 1, Integer::sum);
        }
        if (cjk.size() == 1) {
            frequencies.merge(String.valueOf(cjk.get(0)), 1, Integer::sum);
        }
        return frequencies;
    }

    private static void normalize(float[] vector) {
        double sumOfSquares = 0.0;
        for (float value : vector) {
            sumOfSquares += value * value;
        }
        if (sumOfSquares == 0.0) {
            return;
        }
        float norm = (float) Math.sqrt(sumOfSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
    }
}
