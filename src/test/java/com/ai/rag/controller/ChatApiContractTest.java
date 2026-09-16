package com.ai.rag.controller;

import com.ai.rag.support.IntegrationTestSupport;
import com.jayway.jsonpath.JsonPath;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 聊天 REST 接口契约测试
 *
 * <p>覆盖三件事：
 * <ol>
 *   <li><b>成功响应结构</b>——字段齐全、类型正确、数值自洽（total = input + output）；</li>
 *   <li><b>参数校验</b>——非法入参必须 400 且响应体是统一的 {@code ApiError}；</li>
 *   <li><b>错误响应的信息边界</b>——500 响应体不得把内部实现细节（异常堆栈信息、
 *       数据库连接串、类名）吐给客户端。</li>
 * </ol>
 */
class ChatApiContractTest extends IntegrationTestSupport {

    private static final String ENDPOINT = "/chat/message";

    // ---------- 成功路径 ----------

    @Test
    @DisplayName("正常对话 → 200，响应字段齐全且数值自洽")
    void happyPathReturnsCompleteResponse() throws Exception {
        chatModel.reply("你好，我是助手", new TokenUsage(12, 8));

        mockMvc.perform(apiPost(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("session-happy", "你好")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-happy"))
                .andExpect(jsonPath("$.response").value("你好，我是助手"))
                .andExpect(jsonPath("$.conversationId").exists())
                .andExpect(jsonPath("$.inputTokens").value(12))
                .andExpect(jsonPath("$.outputTokens").value(8))
                .andExpect(jsonPath("$.totalTokens").value(20))
                .andExpect(jsonPath("$.cost").value(greaterThan(0.0)))
                .andExpect(jsonPath("$.conversationTitle").value("你好"))
                .andExpect(jsonPath("$.toolCalls").isArray())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("conversationId 是可解析的数字字符串（对外契约：token 记账依赖它）")
    void conversationIdIsNumericString() throws Exception {
        chatModel.reply("ok", new TokenUsage(1, 1));

        String conversationId = jsonField(apiPost(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("session-conv-id", "你好")), "$.conversationId");

        assertThat(Long.parseLong(conversationId))
                .as("conversationId 必须能解析为 Long，否则 TokenService 会记成 null")
                .isPositive();
    }

    @Test
    @DisplayName("缺省 sessionId → 服务端生成一个，并原样回传")
    void missingSessionIdIsGenerated() throws Exception {
        chatModel.reply("ok", new TokenUsage(1, 1));

        String sessionId = jsonField(apiPost(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"你好\"}"), "$.sessionId");

        assertThat(sessionId).isNotBlank();
    }

    @Test
    @DisplayName("持久化副作用：会话、用户消息、助手消息、token 记账都应落库")
    void happyPathPersistsConversationMessagesAndTokenUsage() throws Exception {
        chatModel.reply("落库校验", new TokenUsage(12, 8));

        mockMvc.perform(apiPost(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("session-persist", "请记录我")))
                .andExpect(status().isOk());

        assertThat(countWhere("conversations", "session_id = 'session-persist'")).isEqualTo(1);
        assertThat(countWhere("messages", "role = 'USER'")).isEqualTo(1);
        assertThat(countWhere("messages", "role = 'ASSISTANT'")).isEqualTo(1);
        assertThat(countWhere("token_usage", "session_id = 'session-persist'")).isEqualTo(1);

        Map<String, Object> usage = jdbcTemplate.queryForMap(
                "SELECT input_tokens, output_tokens, total_tokens FROM token_usage WHERE session_id = 'session-persist'");
        assertThat(((Number) usage.get("input_tokens")).intValue()).isEqualTo(12);
        assertThat(((Number) usage.get("output_tokens")).intValue()).isEqualTo(8);
        assertThat(((Number) usage.get("total_tokens")).intValue()).isEqualTo(20);
    }

    @Test
    @DisplayName("多轮对话：同一 sessionId 复用同一个会话，消息累积")
    void multiTurnReusesConversation() throws Exception {
        chatModel.reply("第一轮", new TokenUsage(5, 5));
        mockMvc.perform(apiPost(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                .content(body("session-multi", "第一句"))).andExpect(status().isOk());

        chatModel.reply("第二轮", new TokenUsage(6, 6));
        mockMvc.perform(apiPost(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                .content(body("session-multi", "第二句"))).andExpect(status().isOk());

        assertThat(countWhere("conversations", "session_id = 'session-multi'"))
                .as("同一 sessionId 不应重复建会话")
                .isEqualTo(1);
        assertThat(countWhere("messages", "conversation_id = "
                + "(SELECT id FROM conversations WHERE session_id = 'session-multi')"))
                .as("两轮应产生 2 条用户消息 + 2 条助手消息")
                .isEqualTo(4);
    }

    // ---------- 参数校验 ----------

    @Test
    @DisplayName("message 缺失 → 400 + 统一 ApiError 结构")
    void missingMessageIsRejected() throws Exception {
        mockMvc.perform(apiPost(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"session-invalid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.detail").value(containsString("message")))
                .andExpect(jsonPath("$.path").value("/api" + ENDPOINT));
    }

    @Test
    @DisplayName("message 为空白字符串 → 400（@NotBlank 生效，空格不算内容）")
    void blankMessageIsRejected() throws Exception {
        mockMvc.perform(apiPost(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("session-blank", "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"));
    }

    @Test
    @DisplayName("message 超过 4000 字符 → 400")
    void oversizedMessageIsRejected() throws Exception {
        String tooLong = "a".repeat(4001);

        mockMvc.perform(apiPost(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("session-long", tooLong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("4000")));
    }

    @Test
    @DisplayName("message 恰好 4000 字符 → 放行（边界值）")
    void exactlyMaxLengthMessageIsAccepted() throws Exception {
        chatModel.reply("ok", new TokenUsage(1, 1));

        mockMvc.perform(apiPost(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("session-boundary", "a".repeat(4000))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("sessionId 超过 100 字符 → 400")
    void oversizedSessionIdIsRejected() throws Exception {
        mockMvc.perform(apiPost(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("s".repeat(101), "你好")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("sessionId")));
    }

    @Test
    @DisplayName("请求体不是合法 JSON → 400，而不是 500")
    void malformedJsonIsRejected() throws Exception {
        mockMvc.perform(apiPost(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求体格式错误"));
    }

    // ---------- 错误响应的信息边界 ----------

    @Test
    @DisplayName("模型调用失败 → 500，且响应体不泄露内部异常细节")
    void internalFailureDoesNotLeakInternals() throws Exception {
        chatModel.failWith(new IllegalStateException(
                "connection refused to jdbc:mysql://prod-db:3306/ai_rag_db?user=root&password=s3cr3t"));

        String body = mockMvc.perform(apiPost(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("session-error", "你好")))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("500 响应体不得包含数据库连接串、口令等内部细节")
                .doesNotContain("jdbc:mysql")
                .doesNotContain("s3cr3t")
                .doesNotContain("IllegalStateException");
    }

    private String body(String sessionId, String message) {
        return "{\"sessionId\":\"" + sessionId + "\",\"message\":\"" + message + "\"}";
    }

    private int countWhere(String table, String where) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
        return count == null ? 0 : count;
    }

    private String jsonField(MockHttpServletRequestBuilder request, String jsonPath) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        Object value = JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
        return value == null ? null : value.toString();
    }
}
