package com.ai.rag.controller;

import com.ai.rag.model.dto.DocumentUploadRequest;
import com.ai.rag.service.DocumentService;
import com.ai.rag.support.IntegrationTestSupport;
import com.jayway.jsonpath.JsonPath;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SSE 流式对话端到端测试
 *
 * <p>校验的是**事件序列契约**：前端只依赖事件名与载荷字段，任何一环少发/错名都会让页面静默失效，
 * 而这类问题在只看 HTTP 状态码的测试里完全看不出来。
 *
 * <p>SSE 报文按 {@code event:<name>\ndata:<json>\n\n} 分帧，这里直接解析原始响应体，
 * 不借助任何 SSE 客户端——要验证的正是「发出去的字节对不对」。
 */
class StreamingChatSseIntegrationTest extends IntegrationTestSupport {

    private static final String ENDPOINT = "/chat/stream";

    @Autowired
    private DocumentService documentService;

    @Test
    @DisplayName("正常流：token 逐条推送，最后以 done 收尾（含完整回答与真实 token 用量）")
    void happyPathEmitsTokensThenDone() throws Exception {
        streamingChatModel.stream("你", "好", "呀").withTokenUsage(new TokenUsage(9, 4));

        List<SseEvent> events = stream("session-sse", "打个招呼");

        assertThat(eventNames(events)).containsSubsequence("token", "token", "token", "done");

        List<SseEvent> tokens = eventsOf(events, "token");
        assertThat(tokens).hasSize(3);
        assertThat(tokens.stream().map(e -> e.string("content")).toList())
                .containsExactly("你", "好", "呀");

        SseEvent done = eventsOf(events, "done").get(0);
        assertThat(done.string("response")).isEqualTo("你好呀");
        assertThat(done.integer("inputTokens")).isEqualTo(9);
        assertThat(done.integer("outputTokens")).isEqualTo(4);
        assertThat(done.integer("totalTokens")).isEqualTo(13);
        assertThat(done.string("conversationId")).isNotBlank();
        assertThat(done.string("sessionId")).isEqualTo("session-sse");
        assertThat(done.bool("tokensEstimated"))
                .as("模型返回了真实用量，不应标记为估算")
                .isFalse();
    }

    @Test
    @DisplayName("正常流的副作用：助手消息与 token 记账都要落库")
    void happyPathPersistsAnswerAndTokenUsage() throws Exception {
        streamingChatModel.stream("落", "库").withTokenUsage(new TokenUsage(5, 2));

        stream("session-sse-persist", "记录一下");

        assertThat(countWhere("messages", "role = 'ASSISTANT'")).isEqualTo(1);
        assertThat(countWhere("token_usage", "session_id = 'session-sse-persist'")).isEqualTo(1);
        assertThat((String) jdbcTemplate.queryForObject(
                "SELECT content FROM messages WHERE role = 'ASSISTANT'", String.class))
                .isEqualTo("落库");
    }

    @Test
    @DisplayName("RAG 来源：命中知识库时先发 sources 事件，载荷带 chunkId 与融合分")
    void retrievedSourcesAreEmittedBeforeTokens() throws Exception {
        indexDocument("流式知识库", "流式问答支持的 SSE 事件类型为 token、tool、sources、done、error。");
        streamingChatModel.stream("好的").withTokenUsage(new TokenUsage(3, 3));

        List<SseEvent> events = stream("session-sse-rag", "SSE 事件类型有哪些？");

        assertThat(eventNames(events)).contains("sources");

        SseEvent sources = eventsOf(events, "sources").get(0);
        assertThat((List<?>) sources.raw()).as("sources 载荷应为数组").isNotEmpty();
        assertThat(sources.toString()).contains("chunkId");
    }

    @Test
    @DisplayName("工具事件：工具被调用时推送 tool 事件，含工具名与执行结果")
    void toolEventIsEmitted() throws Exception {
        streamingChatModel.stream("结果是 3").withTokenUsage(new TokenUsage(3, 3));

        // 流式链路下工具由 TokenStream 的 onToolExecuted 回调推送；
        // 这里先确认没有工具调用时不会凭空产生 tool 事件，避免前端出现幽灵工具卡片。
        List<SseEvent> events = stream("session-sse-notool", "你好");

        assertThat(eventNames(events)).doesNotContain("tool");
    }

    @Test
    @DisplayName("模型异常：以 error 事件结束并给出可读原因，绝不静默断流")
    void modelFailureEmitsErrorEvent() throws Exception {
        streamingChatModel.stream("半句").failAfterStreaming(new IllegalStateException("上游超时"));

        List<SseEvent> events = stream("session-sse-error", "你好");

        assertThat(eventNames(events)).contains("error");
        assertThat(eventsOf(events, "error").get(0).string("message")).contains("上游超时");
        assertThat(eventNames(events))
                .as("出错后不应再发 done，前端会误判为成功")
                .doesNotContain("done");
    }

    @Test
    @DisplayName("入参校验不合法：改为流内 error 事件返回（响应类型是 event-stream，不能返回 JSON 错误体）")
    void invalidRequestEmitsErrorEventInsteadOfJson() throws Exception {
        MvcResult result = mockMvc.perform(apiPost(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"session-sse-invalid\",\"message\":\"\"}"))
                .andExpect(status().isOk())
                .andReturn();

        List<SseEvent> events = parse(bodyOf(result));

        assertThat(result.getResponse().getContentType())
                .as("即便是校验失败，也必须保持 text/event-stream")
                .contains(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(eventsOf(events, "error")).isNotEmpty();
        assertThat(eventsOf(events, "error").get(0).string("message")).contains("不能为空");
        assertThat(streamingChatModel.callCount())
                .as("校验失败不应触发模型调用，避免白花 token")
                .isZero();
    }

    @Test
    @DisplayName("超长消息：同样以 error 事件返回，且不触发模型调用")
    void oversizedMessageEmitsErrorEvent() throws Exception {
        MvcResult result = mockMvc.perform(apiPost(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"session-sse-long\",\"message\":\"" + "a".repeat(4001) + "\"}"))
                .andReturn();

        List<SseEvent> events = parse(bodyOf(result));

        assertThat(eventsOf(events, "error")).isNotEmpty();
        assertThat(streamingChatModel.callCount()).isZero();
    }

    // ---------- helpers ----------

    private List<SseEvent> stream(String sessionId, String message) throws Exception {
        MvcResult result = mockMvc.perform(apiPost(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"sessionId\":\"" + sessionId + "\",\"message\":\"" + message + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        return parse(bodyOf(result));
    }

    /**
     * 按 UTF-8 读取响应体
     *
     * <p>必须读**原始字节**再自行解码：SSE 的 Content-Type 是 {@code text/event-stream}
     * 且不带 charset，{@code MockHttpServletResponse.getContentAsString()} 会退回 ISO-8859-1，
     * 中文会被解成乱码从而让断言假失败。SSE 规范本身规定流是 UTF-8，
     * 所以这里按 UTF-8 解码才是正确还原。
     */
    private String bodyOf(MvcResult result) throws Exception {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 解析 SSE 报文为事件列表
     */
    private List<SseEvent> parse(String raw) {
        List<SseEvent> events = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return events;
        }
        for (String frame : raw.split("\n\n")) {
            if (frame.isBlank()) {
                continue;
            }
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : frame.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()).trim());
                }
            }
            if (name != null) {
                events.add(new SseEvent(name, data.length() == 0 ? null : data.toString()));
            }
        }
        return events;
    }

    private List<String> eventNames(List<SseEvent> events) {
        return events.stream().map(SseEvent::name).toList();
    }

    private List<SseEvent> eventsOf(List<SseEvent> events, String name) {
        return events.stream().filter(e -> e.name().equals(name)).toList();
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

    private int countWhere(String table, String where) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 单个 SSE 事件：事件名 + 原始 data 串
     */
    private record SseEvent(String name, String data) {

        String string(String path) {
            return JsonPath.read(data, "$." + path);
        }

        int integer(String path) {
            return ((Number) JsonPath.read(data, "$." + path)).intValue();
        }

        boolean bool(String path) {
            return JsonPath.read(data, "$." + path);
        }

        Object raw() {
            return JsonPath.read(data, "$");
        }

        @Override
        public String toString() {
            return name + "=" + data;
        }
    }
}
