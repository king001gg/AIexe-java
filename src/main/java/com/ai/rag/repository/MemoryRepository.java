package com.ai.rag.repository;

import com.ai.rag.model.entity.Memory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 记忆数据访问层
 */
@Repository
public interface MemoryRepository extends JpaRepository<Memory, Long> {

    /**
     * 根据会话ID和键查找记忆
     */
    Optional<Memory> findBySessionIdAndKey(String sessionId, String key);

    /**
     * 根据会话ID查找所有记忆
     */
    List<Memory> findBySessionId(String sessionId);

    /**
     * 根据会话ID查找记忆（按创建时间倒序）
     */
    @Query("SELECT m FROM Memory m WHERE m.sessionId = :sessionId ORDER BY m.updatedAt DESC")
    List<Memory> findBySessionIdOrderByUpdatedAtDesc(@Param("sessionId") String sessionId);

    /**
     * 根据会话ID和键前缀查找记忆
     */
    @Query("SELECT m FROM Memory m WHERE m.sessionId = :sessionId AND m.key LIKE CONCAT(:prefix, '%')")
    List<Memory> findBySessionIdAndKeyStartingWith(@Param("sessionId") String sessionId,
                                                @Param("prefix") String prefix);

    /**
     * 根据会话ID删除记忆
     */
    @Transactional
    void deleteBySessionId(String sessionId);

    /**
     * 根据会话ID和键删除记忆
     */
    @Transactional
    void deleteBySessionIdAndKey(String sessionId, String key);

    /**
     * 统计会话的记忆数量
     */
    Long countBySessionId(String sessionId);

    /**
     * 查找最近更新的记忆
     */
    @Query("SELECT m FROM Memory m WHERE m.sessionId = :sessionId ORDER BY m.updatedAt DESC LIMIT :limit")
    List<Memory> findRecentMemories(@Param("sessionId") String sessionId, @Param("limit") int limit);

    /**
     * 查找指定时间范围内的记忆
     */
    @Query("SELECT m FROM Memory m WHERE m.sessionId = :sessionId AND m.updatedAt BETWEEN :startDate AND :endDate")
    List<Memory> findBySessionIdAndUpdatedAtBetween(@Param("sessionId") String sessionId,
                                                   @Param("startDate") LocalDateTime startDate,
                                                   @Param("endDate") LocalDateTime endDate);

    /**
     * 检查记忆是否存在
     */
    boolean existsBySessionIdAndKey(String sessionId, String key);
}