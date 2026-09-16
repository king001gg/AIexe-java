package com.ai.rag.support;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可脚本化的 {@link ChatLanguageModel} 测试桩
 *
 * <p>动机：本机没有可用的 OPENAI_API_KEY，真实模型链路完全无法在测试中执行，
 * 于是整个对话业务（会话落库、token 计量、工具调用、RAG 注入）都成了测试盲区。
 * 用脚本化桩替代后，这些链路可以在无网络、确定性的条件下端到端验证。
 *
 * <p>用法：按调用顺序压入应答，每次 {@code generate} 消费一个；队列取空后返回默认回答。
 * 需要模拟「先调工具、再给答案」的两轮交互时，依次压入工具调用应答和文本应答即可。
 */
public class StubChatLanguageModel implements ChatLanguageModel {

    private static final String DEFAULT_REPLY = "(stub) 默认回答";

    /**
     * 脚本队列必须是并发安全的：并发测试（同一会话连点、多会话并行）会同时从多个线程 poll，
     * 非线程安全的 {@code ArrayDeque} 在这种场景下可能抛异常或丢元素，把测试变成随机失败。
     */
    private final Deque<Response<AiMessage>> scriptedReplies = new ConcurrentLinkedDeque<>();
    private final List<List<ChatMessage>> receivedMessages = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger invocationCount = new AtomicInteger();
    private final AtomicInteger toolSpecificationCalls = new AtomicInteger();
    private volatile List<ToolSpecification> lastToolSpecifications = List.of();
    private volatile RuntimeException failure;

    /**
     * 压入一条纯文本应答
     */
    public StubChatLanguageModel reply(String text, TokenUsage tokenUsage) {
        scriptedReplies.add(Response.from(AiMessage.from(text), tokenUsage));
        return this;
    }

    /**
     * 压入一条「请求调用工具」的应答（AiServices 会据此执行工具并再次调用模型）
     */
    public StubChatLanguageModel replyWithToolCall(String toolName, String arguments) {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("stub-call-" + invocationCount.get())
                .name(toolName)
                .arguments(arguments)
                .build();
        scriptedReplies.add(Response.from(AiMessage.from(request)));
        return this;
    }

    /**
     * 让下一次调用抛出异常，用于验证异常路径
     */
    public StubChatLanguageModel failWith(RuntimeException exception) {
        this.failure = exception;
        return this;
    }

    /**
     * 清空脚本与调用记录
     *
     * <p>桩是上下文级单例，测试之间必须复位，否则脚本队列会被上一个用例消费掉。
     */
    public void reset() {
        scriptedReplies.clear();
        receivedMessages.clear();
        invocationCount.set(0);
        toolSpecificationCalls.set(0);
        lastToolSpecifications = List.of();
        failure = null;
    }

    /** 已发生的模型调用次数（含工具回填后的二次调用） */
    public int invocationCount() {
        return invocationCount.get();
    }

    /** 第 n 次（0-based）调用收到的完整消息列表，用于断言记忆注入/RAG 注入是否生效 */
    public List<ChatMessage> messagesOfCall(int index) {
        return List.copyOf(receivedMessages.get(index));
    }

    public int callCount() {
        return receivedMessages.size();
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        invocationCount.incrementAndGet();
        receivedMessages.add(List.copyOf(messages));
        if (failure != null) {
            throw failure;
        }
        Response<AiMessage> next = scriptedReplies.poll();
        return next != null ? next : Response.from(AiMessage.from(DEFAULT_REPLY));
    }

    /**
     * AiServices 在注册了工具时会走这个重载，必须显式覆写（接口默认实现会抛不支持）
     */
    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        toolSpecificationCalls.incrementAndGet();
        lastToolSpecifications = List.copyOf(toolSpecifications);
        return generate(messages);
    }

    /**
     * 走「带工具声明」重载的调用次数
     *
     * <p>用来验证 AiServices 确实把 {@code @Tool} 注册进来了，而不是静默走了无工具分支。
     */
    public int toolSpecificationCallCount() {
        return toolSpecificationCalls.get();
    }

    /** 最近一次调用携带的工具声明，用于断言工具是否真的被暴露给模型 */
    public List<ToolSpecification> lastToolSpecifications() {
        return lastToolSpecifications;
    }
}
