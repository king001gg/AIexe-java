package com.ai.rag.repository;

import com.ai.rag.model.entity.ToolCall;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具调用记录数据访问层
 */
@Repository
public interface ToolCallRepository extends JpaRepository<ToolCall, Long> {

    /**
     * 根据会话ID查找工具调用记录
     */
    List<ToolCall> findBySessionId(String sessionId);

    /**
     * 根据会话ID和工具名称查找工具调用记录
     */
    List<ToolCall> findBySessionIdAndToolName(String sessionId, String toolName);

    /**
     * 根据会话ID查找工具调用记录（按创建时间倒序）
     */
    @Query("SELECT tc FROM ToolCall tc WHERE tc.sessionId = :sessionId ORDER BY tc.createdAt DESC LIMIT :limit")
    List<ToolCall> findBySessionIdOrderByCreatedAtDesc(@Param("sessionId") String sessionId, @Param("limit") int limit);

    /**
     * 根据会话ID查找工具调用记录
     */
    @Query("SELECT tc FROM ToolCall tc WHERE tc.conversationId = :conversationId ORDER BY tc.createdAt DESC LIMIT :limit")
    List<ToolCall> findByConversationIdOrderByCreatedAtDesc(@Param("conversationId") Long conversationId, @Param("limit") int limit);

    /**
     * 根据工具名称查找工具调用记录
     */
    List<ToolCall> findByToolName(String toolName);

    /**
     * 根据状态查找工具调用记录
     */
    List<ToolCall> findByStatus(ToolCall.Status status);

    /**
     * 统计会话的工具调用次数
     */
    @Query("SELECT COUNT(tc) FROM ToolCall tc WHERE tc.sessionId = :sessionId")
    Long countBySessionId(@Param("sessionId") String sessionId);

    /**
     * 统计工具的调用次数
     */
    @Query("SELECT COUNT(tc) FROM ToolCall tc WHERE tc.toolName = :toolName")
    Long countByToolName(@Param("toolName") String toolName);

    /**
     * 统计指定时间范围内的工具调用记录
     */
    @Query("SELECT tc FROM ToolCall tc WHERE tc.sessionId = :sessionId AND tc.createdAt BETWEEN :startDate AND :endDate")
    List<ToolCall> findBySessionIdAndCreatedAtBetween(@Param("sessionId") String sessionId,
                                                     @Param("startDate") LocalDateTime startDate,
                                                     @Param("endDate") LocalDateTime endDate);

    /**
     * 查找最近N条工具调用记录
     */
    @Query("SELECT tc FROM ToolCall tc ORDER BY tc.createdAt DESC LIMIT :limit")
    List<ToolCall> findRecentToolCalls(@Param("limit") int limit);

    /**
     * 查找成功率最低的工具
     */
    @Query("SELECT tc.toolName, COUNT(tc) as total, " +
           "SUM(CASE WHEN tc.status = 'SUCCESS' THEN 1 ELSE 0 END) as success " +
           "FROM ToolCall tc GROUP BY tc.toolName " +
           "ORDER BY (SUM(CASE WHEN tc.status = 'SUCCESS' THEN 1 ELSE 0 END) / COUNT(tc)) ASC")
    List<Object[]> findLowSuccessRateTools();
}