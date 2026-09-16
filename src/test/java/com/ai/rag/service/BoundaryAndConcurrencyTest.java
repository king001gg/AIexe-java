package com.ai.rag.service;

import com.ai.rag.model.dto.DocumentUploadRequest;
import com.ai.rag.model.entity.Message;
import com.ai.rag.repository.DocumentRepository;
import com.ai.rag.repository.KnowledgeBaseRepository;
import com.ai.rag.support.DeterministicEmbeddingModel;
import com.ai.rag.support.IntegrationTestSupport;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 边界、降级与并发测试
 *
 * <p>三类风险各占一节：
 * <ol>
 *   <li><b>边界</b>：空查询、非法 topK、空结果——检索是每次对话的必经之路，边界处理错会直接打断主流程；</li>
 *   <li><b>降级</b>：向量路挂掉时关键词路必须仍然可用（多路召回的核心承诺）；</li>
 *   <li><b>并发</b>：同一会话的并发请求。这类缺陷在功能测试里永远看不到，
 *       但线上只要有一个用户快速连点就会触发。</li>
 * </ol>
 *
 * <p>并发这一节最初有两条用例（D4/D5）固化的是**缺陷行为**而非期望值，它们的作用是在修复
 * 之前把缺陷钉进测试里。D4/D5 修复后这两条已翻转为回归用例：断言并发创建会话幂等、
 * 并发记账不丢更新（含 `total_tokens` 的精确累计），并各自补了一个独立维度的断言
 * （异常计数器、累计值），以免出现「实现把异常吞掉、用例照样绿」的假守卫。
 */
@Slf4j
class BoundaryAndConcurrencyTest extends IntegrationTestSupport {

    @Autowired
    private RagService ragService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private DeterministicEmbeddingModel embeddingModel;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    /** 竞态窗口只有几毫秒，单轮不保证必现；多轮 + 同时起跑把「偶然不触发」压到可忽略 */
    private static final int RACE_ROUNDS = 4;

    private static final int RACE_THREADS = 12;

    // ==================================================================
    // 一、检索边界
    // ==================================================================

    @Test
    @DisplayName("边界：null / 空白查询直接返回空，不抛异常、不打库")
    void blankQueryReturnsEmptyWithoutException() {
        assertThat(ragService.searchWithFusion(null, null, 5)).isEmpty();
        assertThat(ragService.searchWithFusion("", null, 5)).isEmpty();
        assertThat(ragService.searchWithFusion("   ", null, 5)).isEmpty();
        assertThat(ragService.searchWithFusion("\t\n", null, 5)).isEmpty();
    }

    @Test
    @DisplayName("边界：topK 为 0 或负数返回空，而不是把负数透传给向量库")
    void nonPositiveTopKReturnsEmpty() {
        indexDocument("边界知识库", "这是一段用于测试边界条件的知识库内容。");

        assertThat(ragService.searchWithFusion("边界条件", null, 0)).isEmpty();
        assertThat(ragService.searchWithFusion("边界条件", null, -1)).isEmpty();
        assertThat(ragService.searchWithFusion("边界条件", null, Integer.MIN_VALUE)).isEmpty();
    }

    @Test
    @DisplayName("边界：知识库为空时检索返回空列表，不抛异常")
    void emptyCorpusReturnsEmpty() {
        assertThat(ragService.searchWithFusion("任意查询词", null, 5)).isEmpty();
    }

    @Test
    @DisplayName("边界：不存在的 knowledgeBaseId 不应报错，只是查不到")
    void unknownKnowledgeBaseReturnsEmpty() {
        indexDocument("存在的知识库", "这段内容属于另一个知识库，不应被跨库召回。");

        assertThat(ragService.searchWithFusion("另一个知识库", 999_999_999L, 5)).isEmpty();
    }

    @Test
    @DisplayName("边界：检索结果条数不超过 topK（跨知识库的全库检索同样受限）")
    void resultCountIsBoundedByTopK() {
        // 两条内容写进**同一个**知识库，这样才真正测到「全库检索下 topK 是否生效」。
        // （D6 修复前这里必须拆成两个知识库，否则第二次上传会撞 chunk_id 唯一键）
        indexDocument("多条知识库", "向量检索 关键词检索 融合排序 内容一号。");
        indexDocument("多条知识库", "向量检索 关键词检索 融合排序 内容二号。");

        String query = "向量检索 关键词检索 融合排序";
        assertThat(ragService.searchWithFusion(query, null, 1)).hasSize(1);
        assertThat(ragService.searchWithFusion(query, null, 2)).hasSize(2);
    }

    @Test
    @DisplayName("D6 回归：同一个知识库可以反复追加内容，第二次上传不再失败")
    void secondUploadToSameKnowledgeBaseSucceeds() throws Exception {
        indexDocument("可增量维护的知识库", "第一次上传的内容 ALPHA-1000。");

        MockMultipartFile second = new MockMultipartFile("file", "second.txt", "text/plain",
                "第二次上传的内容 BETA-2000。".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart(CONTEXT_PATH + "/documents/upload")
                        .file(second)
                        .param("knowledgeBaseName", "可增量维护的知识库")
                        .param("chunkSize", "1000")
                        .param("chunkOverlap", "0")
                        .contextPath(CONTEXT_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.documentCount").value(1));

        assertThat(countWhere("documents", "content LIKE '%BETA-2000%'"))
                .as("第二次上传的内容必须真的进库——知识库要能增量维护")
                .isEqualTo(1);

        // 两批内容都在，且都能被检索到
        assertThat(ragService.searchWithFusion("ALPHA-1000", null, 5)).isNotEmpty();
        assertThat(ragService.searchWithFusion("BETA-2000", null, 5)).isNotEmpty();
    }

    @Test
    @DisplayName("D6 回归：chunk_id 跨上传全局唯一，且每个分块可追溯到它来自哪一次上传")
    void chunkIdIsUniqueAcrossUploadsAndTraceable() throws Exception {
        indexDocument("多次上传的知识库", "首批分块 GAMMA-3000。");
        indexDocument("多次上传的知识库", "第二批分块 DELTA-4000。");

        List<String> chunkIds = jdbcTemplate.queryForList(
                "SELECT chunk_id FROM documents ORDER BY id", String.class);

        assertThat(chunkIds)
                .as("chunk_id 必须全局唯一，否则 UNIQUE (knowledge_base_id, chunk_id) 必然冲突")
                .doesNotHaveDuplicates()
                .hasSize(2);

        // 前缀是每次上传各自的 UUID：同一批次内共享前缀，不同批次前缀不同
        assertThat(chunkIds.get(0)).startsWith("doc_").contains("_chunk_");
        assertThat(chunkIds.get(0).substring(0, chunkIds.get(0).indexOf("_chunk_")))
                .as("两次上传属于不同批次，前缀（文档身份）必须不同")
                .isNotEqualTo(chunkIds.get(1).substring(0, chunkIds.get(1).indexOf("_chunk_")));
    }

    @Test
    @DisplayName("边界：topK 远大于命中数时只返回实际命中数，不补空位")
    void oversizedTopKIsCappedByActualHits() {
        indexDocument("小知识库", "短内容 ZETA-1234。");

        var hits = ragService.searchWithFusion("ZETA-1234", null, 100);

        assertThat(hits).isNotEmpty();
        assertThat(hits.size()).isLessThan(100);
        assertThat(hits).allSatisfy(hit -> assertThat(hit.document()).isNotNull());
    }

    @Test
    @DisplayName("边界：超长查询（1 万字符）不抛异常，仍能返回结果")
    void oversizedQueryIsHandled() {
        indexDocument("超长查询知识库", "这里包含标识串 OMEGA-7777。");

        String query = "OMEGA-7777 " + "填充词 ".repeat(3000);

        assertThat(ragService.searchWithFusion(query, null, 5)).isNotEmpty();
    }

    @Test
    @DisplayName("边界：纯标点/纯符号查询不炸词法切分，返回空而不是异常")
    void punctuationOnlyQueryIsHandled() {
        indexDocument("标点知识库", "正常内容。");

        assertThat(ragService.searchWithFusion("，。！？；：（）【】", null, 5)).isEmpty();
    }

    // ==================================================================
    // 二、降级容错
    // ==================================================================

    @Test
    @DisplayName("降级：向量库抛异常时，关键词路仍能独立召回（多路召回的核心承诺）")
    void vectorRouteFailureDegradesToKeywordOnly() {
        indexDocument("降级知识库", "唯一标识串 ZETA-9911 出现在这里。");

        var hits = ragServiceWithBrokenVectorStore()
                .searchWithFusion("ZETA-9911 唯一标识串", null, 5);

        assertThat(hits)
                .as("向量路不可用时，关键词路必须仍能召回")
                .isNotEmpty();
        assertThat(hits.get(0).recalledByKeyword()).isTrue();
        assertThat(hits.get(0).recalledByVector())
                .as("向量路已故障，不应标记为被向量路召回")
                .isFalse();
    }

    @Test
    @DisplayName("降级：嵌入模型抛异常时向量路降级为空，但文档记录仍要落库（否则内容彻底找不回）")
    void embeddingFailureDegradesVectorRouteButKeepsDocuments() {
        DeterministicEmbeddingModel broken = new DeterministicEmbeddingModel() {
            @Override
            public dev.langchain4j.data.embedding.Embedding embedText(String text) {
                throw new IllegalStateException("embedding 服务不可用");
            }
        };
        DocumentService degraded = new DocumentService(
                knowledgeBaseRepository, documentRepository, ragService, broken, embeddingStore);

        MockMultipartFile file = new MockMultipartFile("file", "degraded.txt", "text/plain",
                "嵌入服务挂掉时的兜底内容 DELTA-2200。".getBytes(StandardCharsets.UTF_8));
        DocumentUploadRequest request = new DocumentUploadRequest();
        request.setFile(file);
        request.setKnowledgeBaseName("降级嵌入知识库");
        request.setChunkSize(1000);
        request.setChunkOverlap(0);

        DocumentService.DocumentUploadResult result = degraded.processDocument(file, request);

        assertThat(result.getDocumentCount()).isPositive();
        assertThat(countWhere("documents", "content LIKE '%DELTA-2200%'"))
                .as("嵌入失败也要保留文档记录，否则关键词路也没得可查")
                .isEqualTo(1);
    }

    // ==================================================================
    // 三、并发
    // ==================================================================

    @Test
    @DisplayName("并发：同一会话并发写入消息，条数不应丢失")
    void concurrentMessagesAreAllPersisted() throws Exception {
        conversationService.getOrCreateConversation("session-concurrent-msg", "tester", "gpt-4");

        runConcurrently(RACE_THREADS, true, i -> conversationService.saveMessage(
                "session-concurrent-msg", Message.Role.USER, "并发消息-" + i, 1));

        assertThat(countWhere("messages", "conversation_id = "
                + "(SELECT id FROM conversations WHERE session_id = 'session-concurrent-msg')"))
                .as("并发写入的消息不应丢失")
                .isEqualTo(RACE_THREADS);
    }

    @Test
    @DisplayName("D4 回归：并发首次访问同一 sessionId 必须幂等——只出一条会话，且没人被抛异常")
    void concurrentConversationCreationRaces() throws Exception {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            String sessionId = "session-race-d4-" + round;
            AtomicInteger failures = new AtomicInteger();
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();

            runConcurrently(RACE_THREADS, true, i -> {
                try {
                    conversationService.getOrCreateConversation(sessionId, "tester", "gpt-4");
                } catch (RuntimeException e) {
                    failures.incrementAndGet();
                    firstFailure.compareAndSet(null, e);
                }
            });

            // 把首个异常带进失败信息：不然「期望 0 实际 1」这种红没有任何线索。
            // 缺陷在时会看到 IncorrectResultSizeDataAccessException——重复行一旦存在，
            // 单值查询就炸，这正是 D4「永久打坏」的那一层。
            assertThat(failures.get())
                    .as("第 %d 轮：撞唯一键的线程必须走「重查既有会话」分支，不能把异常抛给调用方（首个异常：%s）",
                            round, firstFailure.get())
                    .isZero();

            // 这个计数器不是装饰：runConcurrently 会把 Throwable 吞掉只打日志，
            // 没有它的话「11 个线程全炸、1 个成功」也能让下面这条 countWhere 通过——
            // 那就是一个假的守卫，守卫的恰恰是 D4 本身。
            assertThat(countWhere("conversations", "session_id = '" + sessionId + "'"))
                    .as("第 %d 轮：并发创建必须幂等，只允许一条会话", round)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("D4 修复后：唯一约束成为最后一道防线——绕过业务层也插不进重复会话，且会话照常可用")
    void duplicateConversationsAreRejectedByUniqueConstraint() throws Exception {
        conversationService.getOrCreateConversation("session-unique", "tester", "gpt-4");

        // 旧行为：这里能插进去，随后 findBySessionId 抛 IncorrectResultSizeDataAccessException，
        // 该 sessionId 之后所有请求永久 500（原用例断言的就是这个）。
        // 新行为：数据库层直接拒绝，重复行从源头不可能出现。
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO conversations (session_id, title, user_name, model) "
                        + "VALUES ('session-unique', '新会话', 'tester', 'gpt-4')"))
                .as("绕过业务层直接插重复行也必须被数据库拒绝")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countWhere("conversations", "session_id = 'session-unique'")).isEqualTo(1);

        // 约束没有把正常路径一起挡住：既有会话照常可用
        chatModel.reply("(stub) 你好，我是助手", new TokenUsage(2, 2));
        mockMvc.perform(apiPost("/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"session-unique\",\"message\":\"你好\"}"))
                .andExpect(status().isOk());
        assertThat(countWhere("messages", "conversation_id = "
                + "(SELECT id FROM conversations WHERE session_id = 'session-unique')"))
                .as("一轮问答的两条消息（用户 + 助手）都应挂在唯一的那条会话上——"
                        + "既证明会话仍可用，也证明约束没有把写入路径连带挡掉")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("D5 回归：同一会话同日并发记账不丢更新、不撞唯一约束，累计用量精确")
    void concurrentTokenUsageRecordingRaces() throws Exception {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            String sessionId = "session-token-race-" + round;
            AtomicInteger failures = new AtomicInteger();
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();

            runConcurrently(RACE_THREADS, true, i -> {
                try {
                    tokenService.recordTokenUsage(sessionId, 1L, 10, 5, "gpt-4");
                } catch (RuntimeException e) {
                    failures.incrementAndGet();
                    firstFailure.compareAndSet(null, e);
                }
            });

            assertThat(failures.get())
                    .as("第 %d 轮：并发记账不应有失败——当天还没有行时多个线程会同时插入，"
                            + "撞唯一键的那几次必须被吞掉并改成累加，而不是把这次用量整笔丢掉（首个异常：%s）",
                            round, firstFailure.get())
                    .isZero();

            assertThat(countWhere("token_usage", "session_id = '" + sessionId + "'"))
                    .as("第 %d 轮：同一会话同日应恰好一行（唯一的累加目标）", round)
                    .isEqualTo(1);

            // 这条才是「不丢更新」的真正证明：只断言「没抛异常」的话，
            // 一个把并发写入静默丢弃的实现同样能通过。
            assertThat(sumTokens(sessionId))
                    .as("第 %d 轮：%d 次并发各记 10+5=15 token，累计必须是 %d",
                            round, RACE_THREADS, RACE_THREADS * 15)
                    .isEqualTo(RACE_THREADS * 15);
        }
    }

    @Test
    @DisplayName("并发：不同会话的并发对话互不干扰，各自正确落库")
    void concurrentChatsOnDifferentSessionsAreIndependent() throws Exception {
        // 回复脚本必须在起跑前一次性压好：桩按调用顺序取，模型调用次数 = 请求数
        for (int i = 0; i < RACE_THREADS; i++) {
            chatModel.reply("回答-" + i, new TokenUsage(2, 2));
        }

        AtomicInteger successes = new AtomicInteger();
        runConcurrently(RACE_THREADS, true, i -> {
            try {
                mockMvc.perform(apiPost("/chat/message")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sessionId\":\"session-parallel-" + i
                                        + "\",\"message\":\"问题" + i + "\"}"))
                        .andExpect(status().isOk());
                successes.incrementAndGet();
            } catch (Exception e) {
                log.warn("并发对话失败：{}", e.toString());
            }
        });

        assertThat(successes.get()).as("并发请求应全部成功").isEqualTo(RACE_THREADS);
        assertThat(countWhere("conversations", "session_id LIKE 'session-parallel-%'"))
                .as("每个会话应恰好一条")
                .isEqualTo(RACE_THREADS);
        assertThat(countWhere("messages", "content LIKE '问题%'"))
                .as("每个请求的用户消息都应落库")
                .isEqualTo(RACE_THREADS);
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /**
     * 造一个「向量路必挂」的 RagService 副本
     *
     * <p>用新实例而不是改共享 Bean：只影响这一个用例，不污染同上下文里的其它测试。
     * {@code @Value} 字段在手动构造时不会被注入，必须显式补齐——否则阈值取 0 会让断言失真。
     */
    private RagService ragServiceWithBrokenVectorStore() {
        EmbeddingStore<TextSegment> brokenStore = mock(EmbeddingStore.class);
        when(brokenStore.search(any(EmbeddingSearchRequest.class)))
                .thenThrow(new IllegalStateException("milvus 连接中断"));

        RagService service = new RagService(documentRepository, knowledgeBaseRepository,
                embeddingModel, brokenStore);
        ReflectionTestUtils.setField(service, "vectorTopK", 20);
        ReflectionTestUtils.setField(service, "keywordTopK", 20);
        ReflectionTestUtils.setField(service, "maxKeywordTerms", 8);
        ReflectionTestUtils.setField(service, "minScore", 0.55);
        ReflectionTestUtils.setField(service, "rrfK", 60);
        return service;
    }

    private void indexDocument(String kbName, String content) {
        MockMultipartFile file = new MockMultipartFile("file", kbName + ".txt", "text/plain",
                content.getBytes(StandardCharsets.UTF_8));

        DocumentUploadRequest request = new DocumentUploadRequest();
        request.setFile(file);
        request.setKnowledgeBaseName(kbName);
        request.setChunkSize(1000);
        request.setChunkOverlap(0);
        documentService.processDocument(file, request);
    }

    /**
     * 并发执行 N 次任务
     *
     * <p>{@code simultaneous=true} 时用起跑闸门让所有线程先就位再同时放行，
     * 尽量把「查完还没插」的窗口重叠起来。单个任务抛出的异常会被收集而不是中断其它线程，
     * 由调用方通过各自的断言暴露。
     */
    private void runConcurrently(int threads, boolean simultaneous, java.util.function.IntConsumer task)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(simultaneous ? 1 : 0);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            int index = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    task.accept(index);
                } catch (Throwable t) {
                    log.warn("并发任务 {} 失败：{}", index, t.toString());
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).as("并发任务应在超时前全部结束").isTrue();
    }

    private int countWhere(String table, String where) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
        return count == null ? 0 : count;
    }

    /** 某会话的累计 token（用于验证并发累加没有丢更新） */
    private int sumTokens(String sessionId) {
        Integer sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total_tokens), 0) FROM token_usage WHERE session_id = ?",
                Integer.class, sessionId);
        return sum == null ? 0 : sum;
    }
}
