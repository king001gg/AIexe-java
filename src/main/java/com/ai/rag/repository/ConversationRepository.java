package com.ai.rag.repository;

import com.ai.rag.model.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 会话数据访问层
 */
@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /**
     * 根据会话ID查找会话
     */
    Optional<Conversation> findBySessionId(String sessionId);

    /**
     * 根据会话ID查找会话（不查询消息）
     */
    @Query("SELECT c FROM Conversation c LEFT JOIN FETCH c.messages WHERE c.sessionId = :sessionId")
    Optional<Conversation> findBySessionIdWithMessages(@Param("sessionId") String sessionId);

    /**
     * 查找用户的会话列表
     */
    @Query("SELECT c FROM Conversation c WHERE c.userName = :userName ORDER BY c.updatedAt DESC")
    List<Conversation> findByUserName(@Param("userName") String userName);

    /**
     * 查找最近的会话
     */
    @Query("SELECT c FROM Conversation c ORDER BY c.updatedAt DESC LIMIT :limit")
    List<Conversation> findRecentConversations(@Param("limit") int limit);

    /**
     * 根据会话ID删除会话
     */
    @Transactional
    void deleteBySessionId(String sessionId);

    /**
     * 检查会话是否存在
     */
    boolean existsBySessionId(String sessionId);

    /**
     * 更新会话标题
     */
    @Modifying
    @Transactional
    @Query("UPDATE Conversation c SET c.title = :title WHERE c.sessionId = :sessionId")
    void updateTitle(@Param("sessionId") String sessionId, @Param("title") String title);

    /**
     * 查找指定时间范围内的会话
     */
    @Query("SELECT c FROM Conversation c WHERE c.createdAt BETWEEN :startDate AND :endDate")
    List<Conversation> findByCreatedAtBetween(@Param("startDate") LocalDateTime startDate,
                                           @Param("endDate") LocalDateTime endDate);
}