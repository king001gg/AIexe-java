package com.ai.rag.service;

import com.ai.rag.model.entity.TokenUsage;
import com.ai.rag.repository.TokenUsageRepository;
import com.ai.rag.util.TokenCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Token使用统计服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private final TokenUsageRepository tokenUsageRepository;

    /**
     * 记录Token使用情况
     */
    @Transactional
    public void recordTokenUsage(String sessionId, Long conversationId, int inputTokens, int outputTokens, String model) {
        LocalDate today = LocalDate.now();

        TokenUsage tokenUsage = tokenUsageRepository.findBySessionIdAndDate(sessionId, today);

        if (tokenUsage == null) {
            tokenUsage = new TokenUsage();
            tokenUsage.setSessionId(sessionId);
            tokenUsage.setConversationId(conversationId);
            tokenUsage.setDate(today);
            tokenUsage.setInputTokens(0);
            tokenUsage.setOutputTokens(0);
            tokenUsage.setTotalTokens(0);
            tokenUsage.setCost(BigDecimal.ZERO);
        }

        // 累加Token数量
        tokenUsage.setInputTokens(tokenUsage.getInputTokens() + inputTokens);
        tokenUsage.setOutputTokens(tokenUsage.getOutputTokens() + outputTokens);
        tokenUsage.setTotalTokens(tokenUsage.getTotalTokens() + inputTokens + outputTokens);

        // 计算成本
        double cost = TokenCounter.calculateCost(inputTokens, outputTokens, model);
        tokenUsage.setCost(tokenUsage.getCost().add(BigDecimal.valueOf(cost)));

        tokenUsageRepository.save(tokenUsage);
    }

    /**
     * 获取会话的Token使用统计
     */
    public Map<String, Object> getSessionTokenStats(String sessionId) {
        List<TokenUsage> usages = tokenUsageRepository.findBySessionId(sessionId);

        int totalInput = usages.stream().mapToInt(TokenUsage::getInputTokens).sum();
        int totalOutput = usages.stream().mapToInt(TokenUsage::getOutputTokens).sum();
        int totalTokens = usages.stream().mapToInt(TokenUsage::getTotalTokens).sum();
        BigDecimal totalCost = usages.stream()
            .map(TokenUsage::getCost)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> stats = new HashMap<>();
        stats.put("sessionId", sessionId);
        stats.put("totalInputTokens", totalInput);
        stats.put("totalOutputTokens", totalOutput);
        stats.put("totalTokens", totalTokens);
        stats.put("totalCost", totalCost);
        stats.put("averageTokensPerDay", usages.isEmpty() ? 0 : totalTokens / usages.size());

        return stats;
    }

    /**
     * 获取日期范围内的Token使用统计
     */
    public Map<String, Object> getDateRangeTokenStats(LocalDate startDate, LocalDate endDate) {
        List<TokenUsage> usages = tokenUsageRepository.findByDateBetween(startDate, endDate);

        int totalInput = usages.stream().mapToInt(TokenUsage::getInputTokens).sum();
        int totalOutput = usages.stream().mapToInt(TokenUsage::getOutputTokens).sum();
        int totalTokens = usages.stream().mapToInt(TokenUsage::getTotalTokens).sum();
        BigDecimal totalCost = usages.stream()
            .map(TokenUsage::getCost)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> stats = new HashMap<>();
        stats.put("startDate", startDate);
        stats.put("endDate", endDate);
        stats.put("totalInputTokens", totalInput);
        stats.put("totalOutputTokens", totalOutput);
        stats.put("totalTokens", totalTokens);
        stats.put("totalCost", totalCost);
        stats.put("averageCostPerDay", usages.isEmpty() ? 0 : totalCost.divide(BigDecimal.valueOf(usages.size())));

        return stats;
    }

    /**
     * 获取Token使用趋势
     */
    public List<Map<String, Object>> getTokenUsageTrend(LocalDate startDate, LocalDate endDate) {
        List<Object[]> trendData = tokenUsageRepository.findTokenUsageTrend(startDate, endDate);

        return trendData.stream().map(data -> {
            Map<String, Object> point = new HashMap<>();
            point.put("date", data[0]);
            point.put("totalTokens", data[1]);
            return point;
        }).toList();
    }

    /**
     * 获取成本最高会话
     */
    public List<Map<String, Object>> getHighestCostSessions(int limit) {
        List<Object[]> costData = tokenUsageRepository.findHighestCostSessions(limit);

        return costData.stream().map(data -> {
            Map<String, Object> session = new HashMap<>();
            session.put("sessionId", data[0]);
            session.put("totalCost", data[1]);
            return session;
        }).toList();
    }
}