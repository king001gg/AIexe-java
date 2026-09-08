package com.ai.rag.service;

import com.ai.rag.model.dto.DocumentUploadRequest;
import com.ai.rag.model.entity.Document;
import com.ai.rag.model.entity.KnowledgeBase;
import com.ai.rag.repository.DocumentRepository;
import com.ai.rag.repository.KnowledgeBaseRepository;
import com.ai.rag.util.DocumentParser;
import com.ai.rag.util.TokenCounter;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文档处理服务
 * 负责文档解析、分块、向量化存储
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final RagService ragService;
    private final EmbeddingModel embeddingModel;
    private final MilvusVectorStore milvusVectorStore;

    /**
     * 上传并处理文档
     */
    @Transactional
    public DocumentUploadResult processDocument(MultipartFile file, DocumentUploadRequest request) {
        log.info("Processing document upload: {}", file.getOriginalFilename());

        try {
            // 1. 创建知识库
            KnowledgeBase knowledgeBase = createOrUpdateKnowledgeBase(request);

            // 2. 解析文档内容
            String content = parseDocument(file);

            // 3. 文档分块
            List<String> chunks = DocumentParser.chunkDocument(
                content,
                request.getChunkSize(),
                request.getChunkOverlap()
            );

            // 4. 向量化并存储
            saveDocumentsWithVectors(knowledgeBase, chunks, file.getOriginalFilename());

            // 5. 更新知识库统计
            ragService.updateKnowledgeBaseStats(knowledgeBase.getId());

            return DocumentUploadResult.success(
                knowledgeBase.getId(),
                chunks.size(),
                TokenCounter.estimateTokens(content)
            );

        } catch (Exception e) {
            log.error("Error processing document", e);
            throw new RuntimeException("Failed to process document", e);
        }
    }

    /**
     * 创建或更新知识库
     */
    private KnowledgeBase createOrUpdateKnowledgeBase(DocumentUploadRequest request) {
        return knowledgeBaseRepository.findByName(request.getKnowledgeBaseName())
            .orElseGet(() -> {
                KnowledgeBase kb = new KnowledgeBase();
                kb.setName(request.getKnowledgeBaseName());
                kb.setDescription(request.getDescription());
                kb.setCreatedBy(request.getCreatedBy());
                kb.setCreatedAt(LocalDateTime.now());
                return knowledgeBaseRepository.save(kb);
            });
    }

    /**
     * 解析文档
     */
    private String parseDocument(MultipartFile file) throws IOException {
        // 保存临时文件
        File tempFile = File.createTempFile("upload_", file.getOriginalFilename());
        file.transferTo(tempFile);

        try {
            // 解析文档内容
            String content = DocumentParser.parseDocument(tempFile);
            tempFile.delete();
            return content;
        } catch (Exception e) {
            tempFile.delete();
            throw e;
        }
    }

    /**
     * 保存文档及其向量
     */
    private void saveDocumentsWithVectors(KnowledgeBase knowledgeBase, List<String> chunks, String originalFileName) {
        log.info("Processing {} chunks for knowledge base: {}", chunks.size(), knowledgeBase.getName());

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);

            // 创建文档实体
            Document document = new Document();
            document.setKnowledgeBase(knowledgeBase);
            document.setTitle(extractTitle(chunk, originalFileName));
            document.setContent(chunk);
            document.setChunkId(generateChunkId(knowledgeBase.getId(), i));
            document.setChunkIndex(i);
            document.setTokens(TokenCounter.estimateTokens(chunk));

            // 生成向量并存储到Milvus
            String vectorId = saveVectorToMilvus(chunk);
            document.setVectorId(vectorId);

            // 保存到数据库
            documentRepository.save(document);
        }
    }

    /**
     * 生成分块ID
     */
    private String generateChunkId(Long knowledgeBaseId, int chunkIndex) {
        return String.format("kb_%d_chunk_%d", knowledgeBaseId, chunkIndex);
    }

    /**
     * 提取标题
     */
    private String extractTitle(String content, String originalFileName) {
        if (content.length() > 100) {
            return content.substring(0, 100) + "...";
        }
        return content;
    }

    /**
     * 保存向量到Milvus
     */
    private String saveVectorToMilvus(String text) {
        String vectorId = UUID.randomUUID().toString();
        try {
            // 生成文本向量
            dev.langchain4j.data.embedding.Embedding embedding = embeddingModel.embed(text).content();
            float[] vector = embedding.vector();

            // 真实插入向量到 Milvus（Milvus 不可用时内部优雅降级）
            milvusVectorStore.insert(vectorId, vector);

            log.info("Saved vector to Milvus with ID: {}", vectorId);
            return vectorId;

        } catch (Exception e) {
            log.error("Error saving vector to Milvus", e);
            // 嵌入失败时仍返回 UUID 作为降级标识，后续可通过关键词检索兜底
            return vectorId;
        }
    }

    /**
     * 删除知识库及其所有文档
     */
    @Transactional
    public void deleteKnowledgeBase(Long knowledgeBaseId) {
        log.info("Deleting knowledge base with ID: {}", knowledgeBaseId);

        // 删除所有文档向量（从Milvus）
        List<Document> documents = documentRepository.findByKnowledgeBaseId(knowledgeBaseId);
        for (Document doc : documents) {
            deleteVectorFromMilvus(doc.getVectorId());
        }

        // 删除文档（数据库）
        documentRepository.deleteByKnowledgeBaseId(knowledgeBaseId);

        // 删除知识库
        knowledgeBaseRepository.deleteById(knowledgeBaseId);
    }

    /**
     * 从Milvus删除向量
     */
    private void deleteVectorFromMilvus(String vectorId) {
        log.info("Deleting vector from Milvus: {}", vectorId);
        milvusVectorStore.delete(vectorId);
    }

    /**
     * 上传结果
     */
    public static class DocumentUploadResult {
        private final Long knowledgeBaseId;
        private final int documentCount;
        private final int totalTokens;
        private final String message;

        private DocumentUploadResult(Long knowledgeBaseId, int documentCount, int totalTokens, String message) {
            this.knowledgeBaseId = knowledgeBaseId;
            this.documentCount = documentCount;
            this.totalTokens = totalTokens;
            this.message = message;
        }

        public static DocumentUploadResult success(Long knowledgeBaseId, int documentCount, int totalTokens) {
            return new DocumentUploadResult(knowledgeBaseId, documentCount, totalTokens, "Document processed successfully");
        }

        public static DocumentUploadResult error(String message) {
            return new DocumentUploadResult(null, 0, 0, message);
        }

        // Getters
        public Long getKnowledgeBaseId() { return knowledgeBaseId; }
        public int getDocumentCount() { return documentCount; }
        public int getTotalTokens() { return totalTokens; }
        public String getMessage() { return message; }
    }
}