package com.ai.rag.model.dto;

import java.util.List;

/**
 * 单条检索评测用例（Stage 4）
 *
 * @param id       用例标识
 * @param question 查询问题
 * @param expected 期望被召回的内容。每一项可以是：
 *                 <ul>
 *                   <li>分块的 {@code chunkId} —— 精确匹配</li>
 *                   <li>关键词 —— 出现在分块标题或正文中即算命中（不区分大小写子串匹配）</li>
 *                 </ul>
 *                 用关键词而非 chunkId 作 ground truth，是因为 chunkId 在上传时随机生成，
 *                 手工编写评测集时无法预知。
 */
public record EvaluationCase(String id, String question, List<String> expected) {

    public EvaluationCase {
        expected = expected == null ? List.of() : List.copyOf(expected);
    }
}
