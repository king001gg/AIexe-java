package com.ai.rag.service;

import com.ai.rag.model.dto.ChatRequest;
import com.ai.rag.model.entity.Conversation;
import com.ai.rag.model.entity.Message;
import com.ai.rag.repository.MemoryRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式对话服务测试
 *
 * 通过注入 mock {@link SseEmitter} 捕获 SSE 事件，覆盖无法在无 API key 环境下实测的
 * 「正常结束」路径：token 推送、done 事件中的真实 token 用量、助手消息落库与用量记账。
 */
class StreamingChatServiceTest {

    private static final String SESSION_ID = "s1";
    private static final Long CONVERSATION_ID = 7L;

    private Assistant assistant;
    private ConversationService conversationService;
    private TokenService tokenService;
    private SseEmitter emitter;
    private final List<String> events = new ArrayList<>();

    private StreamingChatService service;

    @BeforeEach
    void setUp() throws Exception {
        assistant = mock(Assistant.class);
        conversationService = mock(ConversationService.class);
        tokenService = mock(TokenService.class);
        emitter = mock(SseEmitter.class);

        MemoryRepository memoryRepository = mock(MemoryRepository.class);
        when(memoryRepository.findBySessionIdOrderByUpdatedAtDesc(anyString())).thenReturn(List.of());
        MemoryPromptBuilder promptBuilder = new MemoryPromptBuilder(memoryRepository);

        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setSessionId(SESSION_ID);
        when(conversationService.getOrCreateConversation(anyString(), any(), any())).thenReturn(conversation);
        when(conversationService.updateTitleIfDefault(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        // 捕获 SseEmitter 写出的事件载荷
        events.clear();
        doAnswer(invocation -> {
            SseEmitter.SseEventBuilder builder = invocation.getArgument(0);
            events.add(builder.build().stream()
                    .map(data -> String.valueOf(data.getData()))
                    .collect(Collectors.joining("|")));
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        service = new StreamingChatService(assistant, conversationService, promptBuilder, tokenService);
        // @Value 字段在单测中不经 Spring 注入，手动赋值
        ReflectionTestUtils.setField(service, "streamTimeoutMs", 1000L);
        ReflectionTestUtils.setField(service, "modelName", "gpt-4");
    }

    private static ChatRequest request(String message) {
        ChatRequest request = new ChatRequest();
        request.setSessionId(SESSION_ID);
        request.setMessage(message);
        return request;
    }

    private String doneEvent() {
        return events.stream().filter(e -> e.contains("done")).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("正常结束：推送 token 事件，并在 done 事件中给出真实 token 用量")
    void streamsTokensAndReportsRealTokenUsage() {
        when(assistant.stream(eq(SESSION_ID), anyString()))
                .thenReturn(FakeTokenStream.completing(List.of("Hello", " world"),
                        Response.from(AiMessage.from("Hello world"), new TokenUsage(120, 45, 165))));

        service.stream(request("hi"), emitter);

        assertThat(events).anyMatch(e -> e.contains("token") && e.contains("Hello"));
        assertThat(events).anyMatch(e -> e.contains("token") && e.contains(" world"));

        // 真实用量（120/45/165），而非估算值；gpt-4 成本 = 120*0.00003 + 45*0.00006 = 0.0063
        assertThat(doneEvent())
                .contains("inputTokens=120")
                .contains("outputTokens=45")
                .contains("totalTokens=165")
                .contains("tokensEstimated=false")
                .contains("conversationId=" + CONVERSATION_ID)
                .contains("cost=0.0063");
    }

    @Test
    @DisplayName("正常结束：助手消息落库并记录 token 用量")
    void persistsAssistantMessageAndRecordsUsage() {
        when(assistant.stream(eq(SESSION_ID), anyString()))
                .thenReturn(FakeTokenStream.completing(List.of("abc"),
                        Response.from(AiMessage.from("abc"), new TokenUsage(10, 5, 15))));

        service.stream(request("hi"), emitter);

        verify(conversationService).saveMessage(eq(SESSION_ID), eq(Message.Role.USER), eq("hi"), anyInt());
        verify(conversationService).saveMessage(eq(SESSION_ID), eq(Message.Role.ASSISTANT), eq("abc"), eq(5));
        verify(tokenService).recordTokenUsage(eq(SESSION_ID), eq(CONVERSATION_ID), eq(10), eq(5), eq("gpt-4"));
        assertThat(events).anyMatch(e -> e.contains("done"));
    }

    @Test
    @DisplayName("流异常：推送 error 事件且不落库助手消息")
    void reportsErrorWithoutPersistingAssistantMessage() {
        when(assistant.stream(eq(SESSION_ID), anyString()))
                .thenReturn(FakeTokenStream.failing(List.of("partial"), new RuntimeException("boom")));

        service.stream(request("hi"), emitter);

        assertThat(events).anyMatch(e -> e.contains("token") && e.contains("partial"));
        assertThat(events).anyMatch(e -> e.contains("error") && e.contains("boom"));
        assertThat(events).noneMatch(e -> e.contains("done"));

        verify(conversationService, never())
                .saveMessage(eq(SESSION_ID), eq(Message.Role.ASSISTANT), anyString(), anyInt());
        verify(tokenService, never())
                .recordTokenUsage(anyString(), anyLong(), anyInt(), anyInt(), anyString());
    }

    @Test
    @DisplayName("模型未返回用量时回退估算并在 done 事件中标记")
    void fallsBackToEstimateWhenUsageMissing() {
        when(assistant.stream(eq(SESSION_ID), anyString()))
                .thenReturn(FakeTokenStream.completing(List.of("hello", " there"),
                        Response.from(AiMessage.from("hello there"))));

        service.stream(request("hi"), emitter);

        assertThat(doneEvent()).contains("tokensEstimated=true");
    }

    /**
     * 同步触发回调的假 TokenStream，避免依赖真实大模型
     */
    private static final class FakeTokenStream implements TokenStream {

        private final List<String> tokens;
        private final Response<AiMessage> response;
        private final RuntimeException error;

        private Consumer<String> nextConsumer;
        private Consumer<Response<AiMessage>> completeConsumer;
        private Consumer<Throwable> errorConsumer;

        private FakeTokenStream(List<String> tokens, Response<AiMessage> response, RuntimeException error) {
            this.tokens = tokens;
            this.response = response;
            this.error = error;
        }

        static FakeTokenStream completing(List<String> tokens, Response<AiMessage> response) {
            return new FakeTokenStream(tokens, response, null);
        }

        static FakeTokenStream failing(List<String> tokens, RuntimeException error) {
            return new FakeTokenStream(tokens, null, error);
        }

        @Override
        public TokenStream onNext(Consumer<String> consumer) {
            this.nextConsumer = consumer;
            return this;
        }

        @Override
        public TokenStream onRetrieved(Consumer<List<Content>> consumer) {
            return this;
        }

        @Override
        public TokenStream onToolExecuted(Consumer<ToolExecution> consumer) {
            return this;
        }

        @Override
        public TokenStream onComplete(Consumer<Response<AiMessage>> consumer) {
            this.completeConsumer = consumer;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> consumer) {
            this.errorConsumer = consumer;
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            return this;
        }

        @Override
        public void start() {
            tokens.forEach(token -> nextConsumer.accept(token));
            if (error != null) {
                errorConsumer.accept(error);
            } else if (completeConsumer != null) {
                completeConsumer.accept(response);
            }
        }
    }
}
