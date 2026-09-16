package com.ai.rag.repository;

import com.ai.rag.model.entity.TokenUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Token使用统计数据访问层
 */
@Repository
public interface TokenUsageRepository extends JpaRepository<TokenUsage, Long> {

    /**
     * 在数据库端原子累加某会话某天的用量与成本（缺陷 D5）
     *
     * <p><b>为什么必须是单条 UPDATE：</b>原先的记账是「先查 → 在 Java 里读-改-写 → save」，
     * 并发时多个线程读到同一份旧值，后写的覆盖先写的（丢更新）；而且当天还没有行时，
     * 多个线程会同时走插入路径，撞上 {@code uk_session_date (session_id, date)} 唯一约束。
     * 把累加交给数据库一条语句完成，这两个窗口同时消失。
     *
     * <p>{@code inputTokens}/{@code outputTokens}/{@code totalTokens} 与 {@code cost} 的增量
     * 都作为参数传入，而不是在 SQL 里重算——{@code cost} 是 Java 侧按模型定价算出来的
     * （{@link com.ai.rag.util.TokenCounter#calculateCost}），SQL 里没有这份定价表。
     *
     * @return 受影响行数：0 表示当天还没有该会话的记账行，调用方需要先插入
     */
    @Modifying
    @Transactional
    @Query("UPDATE TokenUsage t SET t.inputTokens = t.inputTokens + :inputTokens, "
         + "t.outputTokens = t.outputTokens + :outputTokens, "
         + "t.totalTokens = t.totalTokens + :totalTokens, "
         + "t.cost = t.cost + :cost "
         + "WHERE t.sessionId = :sessionId AND t.date = :date")
    int accumulate(@Param("sessionId") String sessionId,
                   @Param("date") LocalDate date,
                   @Param("inputTokens") int inputTokens,
                   @Param("outputTokens") int outputTokens,
                   @Param("totalTokens") int totalTokens,
                   @Param("cost") BigDecimal cost);

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