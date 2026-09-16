package com.ai.rag.support;

import com.ai.rag.service.Assistant;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试基础设施自检
 *
 * <p>先验证「桩真的被 AiServices 调用了」再写业务断言，否则后续所有集成测试的失败
 * 都可能是基础设施问题而不是被测代码的问题——那会让整轮测试的可信度归零。
 */
class InfrastructureSmokeTest extends IntegrationTestSupport {

    @Autowired
    private Assistant assistant;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Test
    @DisplayName("向量库降级：Milvus 不可用时立即回退内存实现，而不是等 10 秒超时")
    void fallsBackToInMemoryStoreQuickly() {
        assertThat(embeddingStore)
                .as("本机无 Milvus，应为快速降级后的内存实现")
                .isInstanceOf(InMemoryEmbeddingStore.class);
    }

    @Test
    @DisplayName("AiServices 装配：桩模型被真实调用，且能拿到返回内容与 token 用量")
    void stubModelIsActuallyInvoked() {
        chatModel.reply("(stub) 你好，我是助手", new TokenUsage(12, 8));

        Result<String> result = assistant.chat("smoke-session", "你好");

        assertThat(result.content()).isEqualTo("(stub) 你好，我是助手");
        assertThat(result.tokenUsage().inputTokenCount()).isEqualTo(12);
        assertThat(result.tokenUsage().outputTokenCount()).isEqualTo(8);
        assertThat(chatModel.callCount()).isEqualTo(1);
        assertThat(chatModel.messagesOfCall(0))
                .as("用户消息应原样送到模型")
                .anyMatch(m -> m.toString().contains("你好"));
    }

    @Test
    @DisplayName("工具装配：@Tool 方法被注册进模型调用，且走的是「带工具声明」的重载")
    void toolsAreRegisteredWithTheModel() {
        chatModel.reply("(stub) 好", new TokenUsage(1, 1));

        assistant.chat("smoke-tools", "1+2 等于几");

        assertThat(chatModel.toolSpecificationCallCount())
                .as("注册了 @Tool 就必须走 generate(messages, toolSpecifications)")
                .isEqualTo(1);
        assertThat(chatModel.lastToolSpecifications())
                .extracting(ToolSpecification::name)
                .contains("calculator", "weather", "math", "search", "datetime");
    }

    @Test
    @DisplayName("流式装配：stream() 走流式模型桩并推完所有 token")
    void streamingModelIsWired() {
        streamingChatModel.stream("你", "好", "呀").withTokenUsage(new TokenUsage(3, 3));

        List<String> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);

        assistant.stream("smoke-stream", "打个招呼")
                .onNext(received::add)
                .onComplete(response -> done.countDown())
                .onError(error -> done.countDown())
                .start();

        assertThat(await(done)).as("流应在超时前结束").isTrue();
        assertThat(received).containsExactly("你", "好", "呀");
        assertThat(streamingChatModel.callCount()).isEqualTo(1);
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    @DisplayName("确定性嵌入桩：相同文本产出相同向量，共享词元越多余弦相似度越高")
    void embeddingStubIsDeterministicAndSimilarityIsMeaningful() {
        Embedding a = embeddingModel.embedText("Milvus 向量检索配置");
        Embedding b = embeddingModel.embedText("Milvus 向量检索配置");
        Embedding unrelated = embeddingModel.embedText("今天天气不错适合出门散步");

        assertThat(a.vector()).isEqualTo(b.vector());
        assertThat(dot(a, b)).isGreaterThan(dot(a, unrelated));
        assertThat(dot(a, a)).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(1e-4f));
    }

    private static float dot(Embedding left, Embedding right) {
        float[] l = left.vector();
        float[] r = right.vector();
        float sum = 0f;
        for (int i = 0; i < l.length; i++) {
            sum += l[i] * r[i];
        }
        return sum;
    }
}
