package com.ai.rag.repository;

import com.ai.rag.model.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息数据访问层
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    /**
     * 根据会话ID查找消息列表
     */
    List<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    /**
     * 根据会话ID和角色查找消息
     */
    @Query("SELECT m FROM Message m WHERE m.conversation.id = :conversationId AND m.role = :role ORDER BY m.createdAt ASC")
    List<Message> findByConversationIdAndRole(@Param("conversationId") Long conversationId,
                                            @Param("role") Message.Role role);

    /**
     * 查找最近的N条消息
     */
    @Query("SELECT m FROM Message m WHERE m.conversation.id = :conversationId ORDER BY m.createdAt DESC LIMIT :limit")
    List<Message> findRecentMessages(@Param("conversationId") Long conversationId, @Param("limit") int limit);

    /**
     * 根据会话ID删除所有消息
     */
    @Transactional
    void deleteByConversationId(Long conversationId);

    /**
     * 统计会话的消息数量
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.conversation.id = :conversationId")
    Long countByConversationId(@Param("conversationId") Long conversationId);

    /**
     * 统计会话的Token总数
     */
    @Query("SELECT COALESCE(SUM(m.tokens), 0) FROM Message m WHERE m.conversation.id = :conversationId")
    Long sumTokensByConversationId(@Param("conversationId") Long conversationId);

    /**
     * 查找指定时间范围内的消息
     */
    @Query("SELECT m FROM Message m WHERE m.conversation.id = :conversationId AND m.createdAt BETWEEN :startDate AND :endDate")
    List<Message> findByConversationIdAndCreatedAtBetween(@Param("conversationId") Long conversationId,
                                                         @Param("startDate") LocalDateTime startDate,
                                                         @Param("endDate") LocalDateTime endDate);
}