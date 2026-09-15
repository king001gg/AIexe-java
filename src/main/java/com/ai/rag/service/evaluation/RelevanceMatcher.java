package com.ai.rag.service.evaluation;

import com.ai.rag.model.entity.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 相关性判定（Stage 4）
 *
 * <p>ground truth 条目与分块的匹配规则：
 * <ol>
 *   <li>条目等于分块的 {@code chunkId} —— 精确匹配</li>
 *   <li>条目作为子串出现在分块标题或正文中 —— 不区分大小写的包含匹配</li>
 * </ol>
 *
 * <p>纯函数实现，不依赖 Spring，便于单测。
 */
public final class RelevanceMatcher {

    private RelevanceMatcher() {
    }

    /**
     * 单个 ground truth 条目是否被该分块满足
     */
    public static boolean matches(Document document, String expectedEntry) {
        if (document == null || expectedEntry == null || expectedEntry.isBlank()) {
            return false;
        }
        String needle = expectedEntry.trim();

        if (needle.equalsIgnoreCase(document.getChunkId())) {
            return true;
        }
        return containsIgnoreCase(document.getTitle(), needle)
                || containsIgnoreCase(document.getContent(), needle);
    }

    /**
     * 按排名顺序标记每个分块是否相关
     */
    public static List<Boolean> relevanceByRank(List<Document> rankedDocuments, List<String> expected) {
        if (rankedDocuments == null || rankedDocuments.isEmpty() || expected == null || expected.isEmpty()) {
            return List.of();
        }
        List<Boolean> relevance = new ArrayList<>(rankedDocuments.size());
        for (Document document : rankedDocuments) {
            relevance.add(isRelevant(document, expected));
        }
        return relevance;
    }

    /**
     * 找出未被任何召回结果满足的 ground truth 条目
     */
    public static List<String> findMissing(List<Document> rankedDocuments, List<String> expected) {
        if (expected == null || expected.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String entry : expected) {
            boolean satisfied = false;
            if (rankedDocuments != null) {
                for (Document document : rankedDocuments) {
                    if (matches(document, entry)) {
                        satisfied = true;
                        break;
                    }
                }
            }
            if (!satisfied) {
                missing.add(entry);
            }
        }
        return missing;
    }

    /**
     * 该分块是否满足任一 ground truth 条目
     */
    private static boolean isRelevant(Document document, List<String> expected) {
        for (String entry : expected) {
            if (matches(document, entry)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(String text, String needle) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
