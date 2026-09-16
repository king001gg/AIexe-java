package com.ai.rag.controller;

import com.ai.rag.support.IntegrationTestSupport;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话上下文管理端点的行为测试
 *
 * <p>这三个端点直接操作 {@code ChatMemoryStore}，是「上下文窗口」对外的唯一窗口：
 * 用户能看到的「历史记录」与「清空对话」都走这里。此前完全没有测试覆盖。
 */
class ChatContextEndpointTest extends IntegrationTestSupport {

    @Test
    @DisplayName("未开始的会话：上下文为空，而不是 404 或报错")
    void unknownSessionReturnsEmptyContext() throws Exception {
        mockMvc.perform(apiGet("/chat/context/never-existed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("never-existed"))
                .andExpect(jsonPath("$.messageCount").value(0))
                .andExpect(jsonPath("$.messages").isArray());
    }

    @Test
    @DisplayName("对话之后：上下文里应能看到用户与助手两条消息，角色正确")
    void contextReflectsCompletedTurn() throws Exception {
        chatModel.reply("我记住了", new TokenUsage(4, 3));
        mockMvc.perform(apiPost("/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"ctx-session\",\"message\":\"记住这句话\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(apiGet("/chat/context/ctx-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageCount").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.messages[0].content").value("记住这句话"))
                .andExpect(jsonPath("$.messages[1].role").value("AI"))
                .andExpect(jsonPath("$.messages[1].content").value("我记住了"));
    }

    @Test
    @DisplayName("清空上下文：返回 cleared=true，且之后上下文确实为空（幂等）")
    void clearContextRemovesHistory() throws Exception {
        chatModel.reply("回答", new TokenUsage(2, 2));
        mockMvc.perform(apiPost("/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"ctx-clear\",\"message\":\"你好\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(apiDelete("/chat/context/ctx-clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").value(true));

        mockMvc.perform(apiGet("/chat/context/ctx-clear"))
                .andExpect(jsonPath("$.messageCount").value(0));

        // 再次清空不应报错（幂等）
        mockMvc.perform(apiDelete("/chat/context/ctx-clear"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("缺陷 D11：用过工具的会话查上下文不能 500，且应透出工具调用")
    void contextWithToolCallDoesNotFail() throws Exception {
        // AiMessage 在「只发起工具调用、没有文本」时 text() == null。
        // 修复前这里用 Map.of(...) 组装响应，Map.of 不接受 null → NPE → 500。
        chatModel.replyWithToolCall("calculator", "{\"expression\":\"1+2\"}");

        mockMvc.perform(apiPost("/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"ctx-tool\",\"message\":\"1+2 等于几\"}"))
                .andExpect(status().isOk());

        // 一轮工具调用产生 4 条消息：用户提问 → AI 发起工具调用 → 工具结果 → AI 最终应答
        mockMvc.perform(apiGet("/chat/context/ctx-tool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageCount").value(4))
                .andExpect(jsonPath("$.messages[0].role").value("USER"))
                // 发起工具调用的那条 AI 消息没有文本，content 应兜成空串而不是 null
                .andExpect(jsonPath("$.messages[1].role").value("AI"))
                .andExpect(jsonPath("$.messages[1].content").value(""))
                .andExpect(jsonPath("$.messages[1].toolCalls[0].name").value("calculator"))
                .andExpect(jsonPath("$.messages[1].toolCalls[0].arguments").value("{\"expression\":\"1+2\"}"))
                .andExpect(jsonPath("$.messages[2].role").value("TOOL_EXECUTION_RESULT"))
                .andExpect(jsonPath("$.messages[3].role").value("AI"));
    }

    @Test
    @DisplayName("缺陷 D11：用过工具的会话查 token 用量也不能 500")
    void tokenEndpointWithToolCallDoesNotFail() throws Exception {
        chatModel.replyWithToolCall("calculator", "{\"expression\":\"3*4\"}");

        mockMvc.perform(apiPost("/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"tokens-tool\",\"message\":\"3*4 等于几\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(apiGet("/chat/tokens/tokens-tool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTokens").isNumber());
    }

    @Test
    @DisplayName("清空上下文不影响已落库的会话与消息（内存窗口与持久化是两件事）")
    void clearingMemoryDoesNotDeletePersistedMessages() throws Exception {
        chatModel.reply("回答", new TokenUsage(2, 2));
        mockMvc.perform(apiPost("/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"ctx-persist\",\"message\":\"你好\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(apiDelete("/chat/context/ctx-persist")).andExpect(status().isOk());

        Integer persisted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = "
                        + "(SELECT id FROM conversations WHERE session_id = 'ctx-persist')", Integer.class);
        assertThat(persisted)
                .as("清空的是上下文窗口，不应连带删除消息记录——否则等于静默丢数据")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Token 汇总端点：按上下文窗口里的消息估算，并给出总数")
    void tokenEndpointEstimatesFromContext() throws Exception {
        chatModel.reply("这是一段助手回答", new TokenUsage(4, 3));
        mockMvc.perform(apiPost("/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"ctx-tokens\",\"message\":\"这是一段用户提问\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(apiGet("/chat/tokens/ctx-tokens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputTokens").value(greaterThan(0)))
                .andExpect(jsonPath("$.outputTokens").value(greaterThan(0)))
                .andExpect(jsonPath("$.totalTokens").value(greaterThan(0)));
    }

    @Test
    @DisplayName("路径变量中的特殊字符不应引发 500")
    void sessionIdWithSpecialCharactersIsHandled() throws Exception {
        int status = mockMvc.perform(apiGet("/chat/context/a%20b%2Fc"))
                .andReturn().getResponse().getStatus();

        assertThat(status)
                .as("非法的会话标识不应导致服务端异常")
                .isIn(200, 400, 404);
    }
}
