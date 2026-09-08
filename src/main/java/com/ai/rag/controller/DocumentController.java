package com.ai.rag.controller;

import com.ai.rag.model.dto.DocumentUploadRequest;
import com.ai.rag.model.entity.Document;
import com.ai.rag.model.entity.KnowledgeBase;
import com.ai.rag.repository.DocumentRepository;
import com.ai.rag.repository.KnowledgeBaseRepository;
import com.ai.rag.service.DocumentService;
import com.ai.rag.service.RagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RagService ragService;

    /**
     * 上传文档
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @Valid @ModelAttribute DocumentUploadRequest request) {
        log.info("Received document upload request: {}", request.getFile().getOriginalFilename());

        try {
            DocumentService.DocumentUploadResult result = documentService.processDocument(
                request.getFile(),
                request
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("knowledgeBaseId", result.getKnowledgeBaseId());
            response.put("documentCount", result.getDocumentCount());
            response.put("totalTokens", result.getTotalTokens());
            response.put("message", result.getMessage());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error uploading document", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "文档上传失败：" + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 获取所有知识库
     */
    @GetMapping("/knowledge-bases")
    public ResponseEntity<List<KnowledgeBase>> getAllKnowledgeBases() {
        List<KnowledgeBase> knowledgeBases = knowledgeBaseRepository.findAll();
        return ResponseEntity.ok(knowledgeBases);
    }

    /**
     * 获取知识库详情
     */
    @GetMapping("/knowledge-bases/{id}")
    public ResponseEntity<KnowledgeBase> getKnowledgeBase(@PathVariable Long id) {
        return knowledgeBaseRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 获取知识库统计
     */
    @GetMapping("/knowledge-bases/{id}/stats")
    public ResponseEntity<Map<String, Object>> getKnowledgeBaseStats(@PathVariable Long id) {
        try {
            Map<String, Object> stats = ragService.getKnowledgeBaseStats(id);
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error getting knowledge base stats", e);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 删除知识库
     */
    @DeleteMapping("/knowledge-bases/{id}")
    public ResponseEntity<Map<String, Object>> deleteKnowledgeBase(@PathVariable Long id) {
        try {
            documentService.deleteKnowledgeBase(id);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "知识库删除成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error deleting knowledge base", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "知识库删除失败：" + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 获取知识库文档列表
     */
    @GetMapping("/knowledge-bases/{id}/documents")
    public ResponseEntity<List<Document>> getKnowledgeBaseDocuments(@PathVariable Long id) {
        List<Document> documents = documentRepository.findByKnowledgeBaseId(id);
        return ResponseEntity.ok(documents);
    }

    /**
     * 搜索知识库
     */
    @GetMapping("/search")
    public ResponseEntity<List<Document>> searchDocuments(
            @RequestParam String query,
            @RequestParam(required = false) Long knowledgeBaseId,
            @RequestParam(defaultValue = "5") int topK) {
        List<Document> results = ragService.searchRelevantDocuments(query, knowledgeBaseId, topK);
        return ResponseEntity.ok(results);
    }
}