package com.ai.rag.repository;

import com.ai.rag.model.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 知识库数据访问层
 */
@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    /**
     * 根据名称查找知识库
     */
    Optional<KnowledgeBase> findByName(String name);

    /**
     * 根据创建者查找知识库列表
     */
    List<KnowledgeBase> findByCreatedBy(String createdBy);

    /**
     * 查找知识库及其文档
     */
    @Query("SELECT kb FROM KnowledgeBase kb LEFT JOIN FETCH kb.documents WHERE kb.id = :id")
    Optional<KnowledgeBase> findByIdWithDocuments(@Param("id") Long id);

    /**
     * 统计用户的知识库数量
     */
    @Query("SELECT COUNT(kb) FROM KnowledgeBase kb WHERE kb.createdBy = :createdBy")
    Long countByCreatedBy(@Param("createdBy") String createdBy);

    /**
     * 统计知识库的文档总数
     */
    @Query("SELECT COALESCE(SUM(kb.docCount), 0) FROM KnowledgeBase kb WHERE kb.createdBy = :createdBy")
    Long sumDocCountByCreatedBy(@Param("createdBy") String createdBy);

    /**
     * 统计知识库的Token总数
     */
    @Query("SELECT COALESCE(SUM(kb.totalTokens), 0) FROM KnowledgeBase kb WHERE kb.createdBy = :createdBy")
    Long sumTotalTokensByCreatedBy(@Param("createdBy") String createdBy);

    /**
     * 根据创建时间查找知识库
     */
    @Query("SELECT kb FROM KnowledgeBase kb WHERE kb.createdAt BETWEEN :startDate AND :endDate ORDER BY kb.createdAt DESC")
    List<KnowledgeBase> findByCreatedAtBetween(@Param("startDate") LocalDateTime startDate,
                                               @Param("endDate") LocalDateTime endDate);

    /**
     * 更新知识库统计信息
     */
    @Query("UPDATE KnowledgeBase kb SET kb.docCount = :docCount, kb.totalTokens = :totalTokens WHERE kb.id = :id")
    void updateStatistics(@Param("id") Long id, @Param("docCount") Integer docCount, @Param("totalTokens") Integer totalTokens);

    /**
     * 查找热门知识库（按文档数量）
     */
    @Query("SELECT kb FROM KnowledgeBase kb ORDER BY kb.docCount DESC LIMIT :limit")
    List<KnowledgeBase> findPopularBases(@Param("limit") int limit);
}