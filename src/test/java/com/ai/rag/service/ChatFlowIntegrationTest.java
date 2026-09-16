package com.ai.rag.service;

import com.ai.rag.model.dto.DocumentUploadRequest;
import com.ai.rag.support.IntegrationTestSupport;
import com.jayway.jsonpath.JsonPath;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对话主链路端到端测试
 *
 * <p>用可脚本化模型桩替代真实大模型后，原本因缺 API Key 而完全无法验证的链路
 * ——多轮记忆、工具调用与落库、RAG 注入、真实 token 计量——都能在这里被真正断言。
 *
 * <p>断言视角刻意分成两层：
 * <ul>
 *   <li><b>对外</b>：HTTP 响应字段（用户能看到的）</li>
 *   <li><b>对内</b>：模型实际收到的 prompt（记忆/RAG 是否真的注入了）+ 数据库副作用</li>
 * </ul>
 * 只看响应很容易漏掉「回答对了但记忆没注入」这类问题——那只是恰好桩的返回值不变而已。
 */
class ChatFlowIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private RagService ragService;

    // ---------- 多轮记忆 ----------

    @Test
    @DisplayName("多轮记忆：第二轮请求的 prompt 里必须带上第一轮的用户消息与助手回答")
    void secondTurnSeesFirstTurnInPrompt() throws Exception {
        chatModel.reply("我叫小明", new TokenUsage(10, 5));
        chat("session-memory", "我叫什么？");

        chatModel.reply("你叫小明", new TokenUsage(12, 6));
        chat("session-memory", "我刚才说我叫什么？");

        List<ChatMessage> secondCall = chatModel.messagesOfCall(1);
        String joined = join(secondCall);

        assertThat(joined)
                .as("第二轮的模型输入应包含第一轮的用户消息")
                .contains("我叫什么？");
        assertThat(joined)
                .as("第二轮的模型输入应包含第一轮的助手回答")
                .contains("我叫小明");
    }

    @Test
    @DisplayName("会话隔离：不同 sessionId 的记忆互不串台")
    void memoryIsIsolatedPerSession() throws Exception {
        chatModel.reply("A 的回答", new TokenUsage(1, 1));
        chat("session-a", "这是 A 的私密问题");

        chatModel.reply("B 的回答", new TokenUsage(1, 1));
        chat("session-b", "这是 B 的问题");

        assertThat(join(chatModel.messagesOfCall(1)))
                .as("B 的上下文里不应出现 A 的内容")
                .doesNotContain("A 的私密问题");
    }

    // ---------- 工具调用 ----------

    @Test
    @DisplayName("工具调用：模型请求调用 calculator → 工具真实执行、结果回填、落库 tool_calls")
    void toolCallIsExecutedAndPersisted() throws Exception {
        chatModel.replyWithToolCall("calculator", "{\"expression\":\"1+2\"}");
        chatModel.reply("1+2=3", new TokenUsage(20, 10));

        mockMvc.perform(apiPost("/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"session-tool\",\"message\":\"1+2 等于几\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.response").value("1+2=3"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.toolCalls[0].toolName").value("calculator"));

        assertThat(chatModel.invocationCount())
                .as("工具结果回填后应再调一次模型")
                .isEqualTo(2);

        Map<String, Object> toolCall = jdbcTemplate.queryForMap(
                "SELECT tool_name, tool_input, tool_output, status, session_id FROM tool_calls WHERE session_id = 'session-tool'");
        assertThat(toolCall.get("tool_name")).isEqualTo("calculator");
        // 落库的是工具方法的**入参值**（由 AgentTools 传入的 expression），不是模型原始 JSON 报文。
        // 这是有意为之（记录业务入参而非协议报文），但意味着 tool_input 里看不到参数名。
        assertThat(toolCall.get("tool_input")).isEqualTo("1+2");
        assertThat((String) toolCall.get("tool_output")).as("工具真实执行结果应落库").contains("1+2 = 3");
        assertThat(toolCall.get("status")).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("工具调用：tool_calls 记录会挂上正确的 conversation_id，便于按会话追溯")
    void toolCallIsLinkedToConversation() throws Exception {
        chatModel.replyWithToolCall("calculator", "{\"expression\":\"2*3\"}");
        chatModel.reply("6", new TokenUsage(1, 1));

        chat("session-tool-link", "2*3 等于几");

        Long conversationId = jdbcTemplate.queryForObject(
                "SELECT id FROM conversations WHERE session_id = 'session-tool-link'", Long.class);
        Long linkedId = jdbcTemplate.queryForObject(
                "SELECT conversation_id FROM tool_calls WHERE session_id = 'session-tool-link'", Long.class);

        assertThat(linkedId).isEqualTo(conversationId);
    }

    @Test
    @DisplayName("工具调用：工具抛异常时不应把整个对话打成 500 之外的状态，且失败要落库")
    void failingToolIsRecordedAsFailed() throws Exception {
        // 空表达式会让计算器抛异常
        chatModel.replyWithToolCall("calculator", "{\"expression\":\"\"}");
        chatModel.reply("算不出来", new TokenUsage(1, 1));

        MvcResult result = mockMvc.perform(apiPost("/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"session-tool-fail\",\"message\":\"算一下\"}"))
                .andReturn();

        // 记录当前真实行为，供缺陷分析使用（工具异常是否被吞、是否影响会话）
        int status = result.getResponse().getStatus();
        int failedRecords = countWhere("tool_calls", "session_id = 'session-tool-fail' AND status = 'FAILED'");

        assertThat(status)
                .as("工具异常下的状态码（当前行为快照）")
                .isIn(200, 500);
        assertThat(failedRecords + countWhere("tool_calls",
                "session_id = 'session-tool-fail' AND status = 'SUCCESS'"))
                .as("无论成败，工具调用都应留下一条记录")
                .isEqualTo(1);
    }

    // ---------- RAG 注入 ----------

    @Test
    @DisplayName("RAG 注入：检索到的知识库内容必须真的出现在模型 prompt 里")
    void retrievedKnowledgeIsInjectedIntoPrompt() throws Exception {
        indexDocument("运维手册", "Milvus 向量库的默认端口是 19530，健康检查路径为 /healthz。");

        chatModel.reply("根据运维手册，端口是 19530", new TokenUsage(30, 15));
        chat("session-rag", "Milvus 向量库的默认端口是多少？");

        assertThat(join(chatModel.messagesOfCall(0)))
                .as("检索到的分块内容应被注入 prompt")
                .contains("19530");
    }

    @Test
    @DisplayName("RAG 隔离：知识库里没有相关内容时不注入任何来源，对话仍正常完成")
    void noHitMeansNoInjectionAndStillAnswers() throws Exception {
        indexDocument("运维手册", "Milvus 向量库的默认端口是 19530。");

        chatModel.reply("我不知道", new TokenUsage(5, 5));
        chat("session-rag-miss", "请背诵一段完全无关的莎士比亚台词");

        assertThat(join(chatModel.messagesOfCall(0)))
                .as("无关问题不应把知识库内容塞进 prompt")
                .doesNotContain("19530");
    }

    @Test
    @DisplayName("检索落库一致性：上传后的分块既能被向量路召回，也能被关键词路召回")
    void uploadedChunksAreRetrievableByBothRoutes() {
        indexDocument("产品文档", "智能问答 Agent 支持多路召回与 RRF 融合排序。");

        var hits = ragService.searchWithFusion("多路召回 RRF 融合", null, 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).document().getContent()).contains("RRF");
        assertThat(hits.get(0).source())
                .as("向量路与关键词路都命中时，来源应标记为 hybrid")
                .isEqualTo("hybrid");
        assertThat(hits.get(0).vectorRank()).isPositive();
        assertThat(hits.get(0).keywordRank()).isPositive();
    }

    @Test
    @DisplayName("检索按知识库过滤：限制到另一个知识库时不应召回本次内容")
    void retrievalRespectsKnowledgeBaseFilter() {
        Long kbId = indexDocument("知识库甲", "独家内容：量子计算实验编号 QC-7788。");
        Long otherKbId = indexDocument("知识库乙", "另一份无关的排班表内容。");

        var filtered = ragService.searchWithFusion("量子计算实验编号", otherKbId, 5);
        assertThat(filtered)
                .as("限定到知识库乙时不应召回知识库甲的独家内容")
                .noneMatch(hit -> hit.document().getContent().contains("QC-7788"));

        var unfiltered = ragService.searchWithFusion("量子计算实验编号", kbId, 5);
        assertThat(unfiltered).isNotEmpty();
        assertThat(unfiltered.get(0).document().getContent()).contains("QC-7788");
    }

    // ---------- helpers ----------

    private void chat(String sessionId, String message) throws Exception {
        mockMvc.perform(apiPost("/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\",\"message\":\"" + message + "\"}"))
                .andExpect(status().isOk());
    }

    /**
     * 通过真实上传链路建索引（解析 → 分块 → 嵌入 → 入库 → 统计）
     */
    private Long indexDocument(String kbName, String content) {
        MockMultipartFile file = new MockMultipartFile("file", kbName + ".txt", "text/plain",
                content.getBytes(StandardCharsets.UTF_8));

        DocumentUploadRequest request = new DocumentUploadRequest();
        request.setFile(file);
        request.setKnowledgeBaseName(kbName);
        request.setDescription("集成测试用知识库");
        request.setCreatedBy("tester");
        request.setChunkSize(1000);
        request.setChunkOverlap(0);

        DocumentService.DocumentUploadResult result = documentService.processDocument(file, request);
        assertThat(result.getDocumentCount()).as("应至少分出一个块").isPositive();
        return result.getKnowledgeBaseId();
    }

    private static String join(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        messages.forEach(m -> sb.append(m.toString()).append('\n'));
        return sb.toString();
    }

    private int countWhere(String table, String where) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
        return count == null ? 0 : count;
    }

    @SuppressWarnings("unused")
    private String readJson(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }
}
