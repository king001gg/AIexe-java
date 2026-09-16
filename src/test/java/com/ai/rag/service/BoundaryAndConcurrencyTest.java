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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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
 * <p>并发用例的断言写的是**当前实测行为**，其中两条（D4/D5）固化的是缺陷而非期望值，
 * 断言文案里已标明「正确行为应是什么」。修复后这些用例会失败——这正是它们存在的意义：
 * 把缺陷钉在测试里，修复时不可能悄悄溜过去。
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
        // 刻意分两个知识库：写入同一个知识库会触发缺陷 D6（chunk_id 冲突），
        // 那样这个用例就测不到 topK 了
        indexDocument("多条知识库甲", "向量检索 关键词检索 融合排序 内容一号。");
        indexDocument("多条知识库乙", "向量检索 关键词检索 融合排序 内容二号。");

        String query = "向量检索 关键词检索 融合排序";
        assertThat(ragService.searchWithFusion(query, null, 1)).hasSize(1);
        assertThat(ragService.searchWithFusion(query, null, 2)).hasSize(2);
    }

    @Test
    @DisplayName("缺陷 D6：同一个知识库第二次上传必定失败（chunk_id 只由 kbId+分块序号生成）")
    void secondUploadToSameKnowledgeBaseFails() throws Exception {
        indexDocument("只能传一次的知识库", "第一次上传的内容 ALPHA-1000。");

        MockMultipartFile second = new MockMultipartFile("file", "second.txt", "text/plain",
                "第二次上传的内容 BETA-2000。".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart(CONTEXT_PATH + "/documents/upload")
                        .file(second)
                        .param("knowledgeBaseName", "只能传一次的知识库")
                        .param("chunkSize", "1000")
                        .param("chunkOverlap", "0")
                        .contextPath(CONTEXT_PATH))
                .andExpect(status().isInternalServerError());

        assertThat(countWhere("documents", "content LIKE '%BETA-2000%'"))
                .as("第二次上传的内容完全没进去")
                .isZero();
    }

    @Test
    @DisplayName("缺陷 D6 的后果：失败的上传是整体回滚的，用户看到的是无信息量的 500 文案")
    void duplicateChunkIdRollsBackAtomicallyAndHidesTheCause() throws Exception {
        // 一个知识库的名字一旦被用过，就再也传不进新内容——这是 D6 最直接的用户可见后果
        indexDocument("被锁死的知识库", "首个分块 GAMMA-3000。");

        MockMultipartFile second = new MockMultipartFile("file", "second.txt", "text/plain",
                "第二份文件 DELTA-4000。".getBytes(StandardCharsets.UTF_8));

        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart(CONTEXT_PATH + "/documents/upload")
                        .file(second)
                        .param("knowledgeBaseName", "被锁死的知识库")
                        .param("chunkSize", "1000")
                        .param("chunkOverlap", "0")
                        .contextPath(CONTEXT_PATH))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .as("错误文案被 DocumentService 的统一包装吞掉了根因，运维无法据此定位")
                .contains("Failed to process document")
                .doesNotContain("uk_knowledge_chunk");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM documents", Integer.class))
                .as("失败的上传不应留下半个分块")
                .isEqualTo(1);
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
    @DisplayName("缺陷 D4：并发首次访问同一 sessionId 会创建出重复会话（先查后插 + session_id 无唯一约束）")
    void concurrentConversationCreationRaces() throws Exception {
        int duplicated = 0;

        for (int round = 0; round < RACE_ROUNDS && duplicated <= 1; round++) {
            String sessionId = "session-race-d4-" + round;
            runConcurrently(RACE_THREADS, true,
                    i -> conversationService.getOrCreateConversation(sessionId, "tester", "gpt-4"));
            duplicated = countWhere("conversations", "session_id = '" + sessionId + "'");
        }

        // 正确行为：并发下也应幂等，恒为 1（需要 session_id 唯一约束 + 撞唯一键后重查）。
        // 当前行为：findBySessionId 全部查不到 → 各自 insert → 产生多条。
        assertThat(duplicated)
                .as("当前行为快照（缺陷 D4）：并发首次访问产出了 %d 个会话；正确实现应恒为 1", duplicated)
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("缺陷 D4 的后果：一旦出现重复会话，该 sessionId 之后所有请求都永久 500")
    void duplicateConversationsBreakTheSessionPermanently() throws Exception {
        // 直接构造出竞态的结果，不依赖线程调度，断言稳定可复现
        for (int i = 0; i < 2; i++) {
            jdbcTemplate.update("INSERT INTO conversations (session_id, title, user_name, model) "
                    + "VALUES ('session-duplicated', '新会话', 'tester', 'gpt-4')");
        }

        mockMvc.perform(apiPost("/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"session-duplicated\",\"message\":\"你好\"}"))
                .andExpect(status().isInternalServerError());

        assertThat(chatModel.invocationCount())
                .as("会话查询就已经失败，不应白花一次模型调用")
                .isZero();
    }

    @Test
    @DisplayName("缺陷 D5：同一会话同日并发记账会撞唯一约束 uk_session_date")
    void concurrentTokenUsageRecordingRaces() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        int rows = 0;

        for (int round = 0; round < RACE_ROUNDS && failures.get() == 0; round++) {
            String sessionId = "session-token-race-" + round;
            runConcurrently(RACE_THREADS, true, i -> {
                try {
                    tokenService.recordTokenUsage(sessionId, 1L, 10, 5, "gpt-4");
                } catch (RuntimeException e) {
                    failures.incrementAndGet();
                }
            });
            rows = countWhere("token_usage", "session_id = '" + sessionId + "'");
        }

        // 正确行为：并发累加不应失败（撞唯一键后重查再累加，或改为数据库端原子 upsert）。
        // 当前行为：先查后插，多个线程同时查不到 → 同时 insert → 唯一键冲突。
        assertThat(failures.get())
                .as("当前行为快照（缺陷 D5）：%d 次并发记账抛异常；该会话最终只有 %d 行、累计 token 不足",
                        failures.get(), rows)
                .isPositive();
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
}
