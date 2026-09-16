package com.ai.rag.service;

import com.ai.rag.model.entity.TokenUsage;
import com.ai.rag.repository.TokenUsageRepository;
import com.ai.rag.util.TokenCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

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
     *
     * <p><b>为什么这样写（缺陷 D5）：</b>原先是「先查后插 + Java 侧读-改-写」，
     * 同一会话同日并发记账时有两个互相独立的失败模式：
     * <ol>
     *   <li><b>丢更新</b>：多个线程读到同一份旧值，各自加完再 save，后写的覆盖先写的
     *       ——账单比真实用量少；</li>
     *   <li><b>撞唯一约束</b>：当天还没有行时多个线程同时插入，只有一个能成功，
     *       其余抛 {@code DataIntegrityViolationException}，这次用量**整笔丢失**。</li>
     * </ol>
     *
     * <p><b>为什么本方法不带 {@code @Transactional}：</b>捕获唯一键冲突必须发生在
     * <b>事务边界之外</b>——冲突会把当前事务标记为 rollback-only，在同一个事务里继续
     * 做任何事都不可能成功（连再查一次都拿不到干净结果）。去掉注解后，下面每一次
     * repository 调用各自成事务，{@code catch} 处已经在失败的那个事务之外了。
     * {@code AgentService} / {@code StreamingChatService} 都不在事务里调用本方法，
     * 所以事务语义没有变化。
     */
    public void recordTokenUsage(String sessionId, Long conversationId, int inputTokens, int outputTokens, String model) {
        LocalDate today = LocalDate.now();

        // cost 是 Java 侧按模型定价算出来的，必须作为参数交给数据库累加
        BigDecimal cost = BigDecimal.valueOf(TokenCounter.calculateCost(inputTokens, outputTokens, model));
        int totalTokens = inputTokens + outputTokens;

        // 1) 数据库端原子累加。命中就结束，不存在「读-改-写」窗口。
        if (tokenUsageRepository.accumulate(sessionId, today, inputTokens, outputTokens, totalTokens, cost) > 0) {
            return;
        }

        // 2) 当天还没有该会话的行 → 插入。并发下可能撞 uk_session_date。
        try {
            tokenUsageRepository.saveAndFlush(newDailyRow(sessionId, conversationId, today));
        } catch (DataIntegrityViolationException e) {
            // 别的线程抢先插入了。此处已不在那个失败的事务里，可以安全地走下面的累加。
            log.debug("会话 {} 当日记账行已被并发创建，本次改为累加到既有行", sessionId);
        }

        tokenUsageRepository.accumulate(sessionId, today, inputTokens, outputTokens, totalTokens, cost);
    }

    /**
     * 当天的空记账行（先插入再累加，避免在 Java 里做读-改-写）
     *
     * <p>字段与初始值照搬原先 {@code recordTokenUsage} 里的写法，
     * {@code created_at} 由实体上的 {@code @CreationTimestamp} 负责。
     */
    private TokenUsage newDailyRow(String sessionId, Long conversationId, LocalDate date) {
        TokenUsage row = new TokenUsage();
        row.setSessionId(sessionId);
        row.setConversationId(conversationId);
        row.setDate(date);
        row.setInputTokens(0);
        row.setOutputTokens(0);
        row.setTotalTokens(0);
        row.setCost(BigDecimal.ZERO);
        return row;
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