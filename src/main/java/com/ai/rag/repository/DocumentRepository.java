package com.ai.rag.repository;

import com.ai.rag.model.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 文档数据访问层
 */
@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    /**
     * 根据知识库ID查找文档列表
     */
    List<Document> findByKnowledgeBaseId(Long knowledgeBaseId);

    /**
     * 根据知识库ID和分块ID查找文档
     */
    Document findByKnowledgeBaseIdAndChunkId(Long knowledgeBaseId, String chunkId);

    /**
     * 根据知识库ID删除所有文档
     */
    @Transactional
    void deleteByKnowledgeBaseId(Long knowledgeBaseId);

    /**
     * 统计知识库的文档数量
     */
    Long countByKnowledgeBaseId(Long knowledgeBaseId);

    /**
     * 统计知识库的Token总数
     */
    @Query("SELECT COALESCE(SUM(d.tokens), 0) FROM Document d WHERE d.knowledgeBase.id = :knowledgeBaseId")
    Long sumTokensByKnowledgeBaseId(@Param("knowledgeBaseId") Long knowledgeBaseId);

    /**
     * 根据关键词搜索文档标题和内容
     */
    @Query("SELECT d FROM Document d WHERE d.knowledgeBase.id = :knowledgeBaseId " +
           "AND (LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(d.content) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "ORDER BY " +
           "CASE WHEN LOWER(d.title) LIKE LOWER(CONCAT(:keyword, '%')) THEN 0 ELSE 1 END, " +
           "d.chunkIndex")
    List<Document> searchByKeyword(@Param("knowledgeBaseId") Long knowledgeBaseId,
                                 @Param("keyword") String keyword);

    /**
     * 查找文档的向量ID列表
     */
    @Query("SELECT d.vectorId FROM Document d WHERE d.knowledgeBase.id = :knowledgeBaseId AND d.vectorId IS NOT NULL")
    List<String> findVectorIdsByKnowledgeBaseId(@Param("knowledgeBaseId") Long knowledgeBaseId);

    /**
     * 批量更新向量ID
     */
    @Modifying
    @Transactional
    @Query("UPDATE Document d SET d.vectorId = :vectorId WHERE d.id = :id")
    void updateVectorId(@Param("id") Long id, @Param("vectorId") String vectorId);

    /**
     * 根据向量ID列表查找文档
     */
    List<Document> findByVectorIdIn(List<String> vectorIds);
}