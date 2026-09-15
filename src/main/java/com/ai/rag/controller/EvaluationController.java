package com.ai.rag.controller;

import com.ai.rag.model.dto.EvaluationDataset;
import com.ai.rag.model.dto.EvaluationReport;
import com.ai.rag.service.evaluation.RetrievalEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 评测控制器（Stage 4）
 *
 * <p>用于离线评估检索质量，作为调参（vector-top-k / min-score / rrf-k）的回归基线。
 */
@Slf4j
@RestController
@RequestMapping("/evaluation")
@RequiredArgsConstructor
public class EvaluationController {

    private final RetrievalEvaluator retrievalEvaluator;

    /**
     * 运行检索评测
     *
     * <p>请求体可省略（全用配置默认值）：
     * <pre>
     * {
     *   "dataset": "classpath:evaluation/retrieval-cases.json",
     *   "knowledgeBaseId": 1,
     *   "topK": 5
     * }
     * </pre>
     */
    @PostMapping("/retrieval")
    public ResponseEntity<EvaluationReport> runRetrievalEvaluation(
            @RequestBody(required = false) RetrievalEvaluationRequest request) {
        RetrievalEvaluationRequest actual = request == null
                ? new RetrievalEvaluationRequest(null, null, null) : request;

        log.info("Received retrieval evaluation request: dataset={}, knowledgeBaseId={}, topK={}",
                actual.dataset(), actual.knowledgeBaseId(), actual.topK());

        return ResponseEntity.ok(retrievalEvaluator.evaluate(
                actual.dataset(), actual.knowledgeBaseId(), actual.topK()));
    }

    /**
     * 查看当前生效的评测数据集内容（便于确认 ground truth 是否符合预期）
     */
    @GetMapping("/dataset")
    public ResponseEntity<EvaluationDataset> getDataset() {
        return ResponseEntity.ok(retrievalEvaluator.loadDataset(null));
    }

    /**
     * 评测请求体（字段均可省略）
     */
    public record RetrievalEvaluationRequest(String dataset, Long knowledgeBaseId, Integer topK) {
    }

    /**
     * 评测能力说明（便于发现端点）
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        return ResponseEntity.ok(Map.of(
                "endpoint", "POST /api/evaluation/retrieval",
                "metrics", "recallAtK / precisionAtK / mrr / hitRate",
                "note", "只评测检索，不评测生成质量"
        ));
    }
}
