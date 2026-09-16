package com.ai.rag.support;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可脚本化的 {@link StreamingChatLanguageModel} 测试桩
 *
 * <p>同步地、立即地把预设 token 逐个推给 handler，然后触发完成或错误回调，
 * 使 SSE 链路（token/sources/tool/done/error 事件）可以在测试中确定性复现——
 * 无需真实模型，也无需等待真实网络流。
 */
public class StubStreamingChatLanguageModel implements StreamingChatLanguageModel {

    private final List<String> tokens = new ArrayList<>();
    private final List<List<ChatMessage>> receivedMessages = Collections.synchronizedList(new ArrayList<>());
    private TokenUsage tokenUsage;
    private RuntimeException failure;
    private final AtomicBoolean started = new AtomicBoolean();

    /**
     * 设定要推送的 token 与最终用量
     */
    public StubStreamingChatLanguageModel stream(String... tokenParts) {
        this.tokens.clear();
        Collections.addAll(this.tokens, tokenParts);
        return this;
    }

    public StubStreamingChatLanguageModel withTokenUsage(TokenUsage tokenUsage) {
        this.tokenUsage = tokenUsage;
        return this;
    }

    /**
     * 推送完 token 后以异常结束，用于验证 SSE 的 error 事件路径
     */
    public StubStreamingChatLanguageModel failAfterStreaming(RuntimeException exception) {
        this.failure = exception;
        return this;
    }

    /**
     * 清空脚本与调用记录（桩为上下文级单例，用例之间必须复位）
     */
    public void reset() {
        tokens.clear();
        receivedMessages.clear();
        tokenUsage = null;
        failure = null;
        started.set(false);
    }

    public int callCount() {
        return receivedMessages.size();
    }

    public List<ChatMessage> messagesOfCall(int index) {
        return List.copyOf(receivedMessages.get(index));
    }

    public boolean wasStarted() {
        return started.get();
    }

    @Override
    public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
        started.set(true);
        receivedMessages.add(List.copyOf(messages));

        for (String token : tokens) {
            handler.onNext(token);
        }

        if (failure != null) {
            handler.onError(failure);
            return;
        }

        Response<AiMessage> response = tokenUsage == null
                ? Response.from(AiMessage.from(String.join("", tokens)))
                : Response.from(AiMessage.from(String.join("", tokens)), tokenUsage);
        handler.onComplete(response);
    }

    /**
     * 注册了工具时会走这个重载
     */
    @Override
    public void generate(List<ChatMessage> messages,
                         List<ToolSpecification> toolSpecifications,
                         StreamingResponseHandler<AiMessage> handler) {
        generate(messages, handler);
    }
}
