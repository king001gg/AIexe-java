package com.ai.rag.service;

import com.ai.rag.util.TokenCounter;
import dev.langchain4j.model.output.TokenUsage;

/**
 * Token 用量解析（Stage 3）
 *
 * <p>优先采用大模型返回的**真实 token 用量**（{@link TokenUsage}）；
 * 当模型/网关未返回用量（字段为 null）时，回退到 {@link TokenCounter} 的字符估算，
 * 并通过 {@code estimated} 标记让调用方与前端能区分两者。
 */
public final class TokenUsageResolver {

    private TokenUsageResolver() {
    }

    /**
     * 解析结果
     *
     * @param inputTokens  输入 token 数
     * @param outputTokens 输出 token 数
     * @param totalTokens  总 token 数
     * @param estimated    true 表示该数值为估算值（模型未返回用量）
     */
    public record Resolved(int inputTokens, int outputTokens, int totalTokens, boolean estimated) {
    }

    /**
     * 解析 token 用量
     *
     * @param actual     模型返回的真实用量，可为 null
     * @param inputText  用于估算的输入文本（prompt / 请求消息）
     * @param outputText 用于估算的输出文本（模型回答）
     */
    public static Resolved resolve(TokenUsage actual, String inputText, String outputText) {
        if (actual != null && actual.inputTokenCount() != null && actual.outputTokenCount() != null) {
            int input = actual.inputTokenCount();
            int output = actual.outputTokenCount();
            int total = actual.totalTokenCount() != null ? actual.totalTokenCount() : input + output;
            return new Resolved(input, output, total, false);
        }

        int input = TokenCounter.estimateTokens(inputText);
        int output = TokenCounter.estimateTokens(outputText);
        return new Resolved(input, output, input + output, true);
    }
}
