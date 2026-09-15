package com.ai.rag.service;

import com.ai.rag.model.dto.DocumentUploadRequest;
import com.ai.rag.model.entity.Document;
import com.ai.rag.model.entity.KnowledgeBase;
import com.ai.rag.repository.DocumentRepository;
import com.ai.rag.repository.KnowledgeBaseRepository;
import com.ai.rag.util.DocumentParser;
import com.ai.rag.util.TokenCounter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
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
    private final EmbeddingStore<TextSegment> embeddingStore;

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

            // 生成向量并存储到向量库（Milvus 不可用时内部优雅降级）
            Metadata metadata = new Metadata()
                    .put("chunkId", document.getChunkId())
                    .put("knowledgeBaseId", String.valueOf(knowledgeBase.getId()))
                    .put("title", document.getTitle());
            String vectorId = saveVectorToEmbeddingStore(chunk, metadata);
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
     * 生成向量并写入向量库
     */
    private String saveVectorToEmbeddingStore(String text, Metadata metadata) {
        try {
            // 生成文本向量
            Embedding embedding = embeddingModel.embed(text).content();

            // 写入向量库（Milvus 不可用时 embeddingStore 已回退为内存实现）
            String vectorId = embeddingStore.add(embedding, TextSegment.from(text, metadata));

            log.info("Saved vector to embedding store with ID: {}", vectorId);
            return vectorId;

        } catch (Exception e) {
            log.error("Error saving vector to embedding store", e);
            // 嵌入失败时仍返回 UUID 作为降级标识，后续可通过关键词检索兜底
            return UUID.randomUUID().toString();
        }
    }

    /**
     * 删除知识库及其所有文档
     */
    @Transactional
    public void deleteKnowledgeBase(Long knowledgeBaseId) {
        log.info("Deleting knowledge base with ID: {}", knowledgeBaseId);

        // 删除所有文档向量（从向量库）
        List<Document> documents = documentRepository.findByKnowledgeBaseId(knowledgeBaseId);
        for (Document doc : documents) {
            deleteVectorFromEmbeddingStore(doc.getVectorId());
        }

        // 删除文档（数据库）
        documentRepository.deleteByKnowledgeBaseId(knowledgeBaseId);

        // 删除知识库
        knowledgeBaseRepository.deleteById(knowledgeBaseId);
    }

    /**
     * 从向量库删除向量
     */
    private void deleteVectorFromEmbeddingStore(String vectorId) {
        if (vectorId == null) {
            return;
        }
        log.info("Deleting vector from embedding store: {}", vectorId);
        try {
            embeddingStore.remove(vectorId);
        } catch (Exception e) {
            log.warn("删除向量失败：{}", e.getMessage());
        }
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
