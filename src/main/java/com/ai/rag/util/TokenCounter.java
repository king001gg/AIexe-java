package com.ai.rag.util;

import lombok.extern.slf4j.Slf4j;

/**
 * Token计数工具类
 */
@Slf4j
public class TokenCounter {

    /**
     * 估算文本的Token数量
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        // 简单估算：平均每个单词1.3个Token
        String[] words = text.split("\\s+");
        return (int) (words.length * 1.3);
    }

    /**
     * 计算JSON字符串的Token数量
     */
    public static int countJsonTokens(String json) {
        return estimateTokens(json);
    }

    /**
     * 计算Token成本（OpenAI定价）
     */
    public static double calculateCost(int inputTokens, int outputTokens, String model) {
        double inputPrice = 0.0;
        double outputPrice = 0.0;

        switch (model) {
            case "gpt-4":
                inputPrice = 0.00003;  // $0.03 per 1K tokens
                outputPrice = 0.00006; // $0.06 per 1K tokens
                break;
            case "gpt-4-turbo":
                inputPrice = 0.00001;  // $0.01 per 1K tokens
                outputPrice = 0.00003; // $0.03 per 1K tokens
                break;
            case "text-embedding-ada-002":
                inputPrice = 0.0000001; // $0.0001 per 1K tokens
                break;
            default:
                inputPrice = 0.00002;
                outputPrice = 0.00004;
        }

        return (inputTokens * inputPrice / 1000) + (outputTokens * outputPrice / 1000);
    }

    /**
     * 格式化Token数量显示
     */
    public static String formatTokenCount(int tokens) {
        if (tokens >= 1000) {
            return String.format("%.1fK", tokens / 1000.0);
        }
        return String.valueOf(tokens);
    }
}