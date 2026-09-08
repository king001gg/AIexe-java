package com.ai.rag.repository;

import com.ai.rag.model.entity.TokenUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Token使用统计数据访问层
 */
@Repository
public interface TokenUsageRepository extends JpaRepository<TokenUsage, Long> {

    /**
     * 根据会话ID和日期查找Token使用记录
     */
    TokenUsage findBySessionIdAndDate(String sessionId, LocalDate date);

    /**
     * 根据日期查找所有Token使用记录
     */
    List<TokenUsage> findByDate(LocalDate date);

    /**
     * 根据日期范围查找Token使用记录
     */
    List<TokenUsage> findByDateBetween(LocalDate startDate, LocalDate endDate);

    /**
     * 根据会话ID查找Token使用记录
     */
    List<TokenUsage> findBySessionId(String sessionId);

    /**
     * 统计指定日期的Token总数
     */
    @Query("SELECT COALESCE(SUM(tu.totalTokens), 0) FROM TokenUsage tu WHERE tu.date = :date")
    Long sumTotalTokensByDate(@Param("date") LocalDate date);

    /**
     * 统计指定日期的成本
     */
    @Query("SELECT COALESCE(SUM(tu.cost), 0) FROM TokenUsage tu WHERE tu.date = :date")
    BigDecimal sumCostByDate(@Param("date") LocalDate date);

    /**
     * 统计会话的总Token使用量
     */
    @Query("SELECT COALESCE(SUM(tu.totalTokens), 0) FROM TokenUsage tu WHERE tu.sessionId = :sessionId")
    Long sumTotalTokensBySessionId(@Param("sessionId") String sessionId);

    /**
     * 统计会话的总成本
     */
    @Query("SELECT COALESCE(SUM(tu.cost), 0) FROM TokenUsage tu WHERE tu.sessionId = :sessionId")
    BigDecimal sumCostBySessionId(@Param("sessionId") String sessionId);

    /**
     * 查找指定日期范围内的用户Token使用排名
     */
    @Query("SELECT tu.sessionId, COALESCE(SUM(tu.totalTokens), 0) as totalTokens " +
           "FROM TokenUsage tu WHERE tu.date BETWEEN :startDate AND :endDate " +
           "GROUP BY tu.sessionId ORDER BY totalTokens DESC LIMIT :limit")
    List<Object[]> findTokenUsageRanking(@Param("startDate") LocalDate startDate,
                                        @Param("endDate") LocalDate endDate,
                                        @Param("limit") int limit);

    /**
     * 统计最近N天的Token使用趋势
     */
    @Query("SELECT tu.date, COALESCE(SUM(tu.totalTokens), 0) as totalTokens " +
           "FROM TokenUsage tu WHERE tu.date BETWEEN :startDate AND :endDate " +
           "GROUP BY tu.date ORDER BY tu.date")
    List<Object[]> findTokenUsageTrend(@Param("startDate") LocalDate startDate,
                                      @Param("endDate") LocalDate endDate);

    /**
     * 查找成本最高的会话
     */
    @Query("SELECT tu.sessionId, COALESCE(SUM(tu.cost), 0) as totalCost " +
           "FROM TokenUsage tu GROUP BY tu.sessionId ORDER BY totalCost DESC LIMIT :limit")
    List<Object[]> findHighestCostSessions(@Param("limit") int limit);

    /**
     * 检查当天是否已有Token使用记录
     */
    boolean existsBySessionIdAndDate(String sessionId, LocalDate date);
}