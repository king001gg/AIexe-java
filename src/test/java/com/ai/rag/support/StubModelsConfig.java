package com.ai.rag.support;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 用可脚本化的桩替换真实模型（测试专用配置）
 *
 * <p>为什么需要：本机没有可用的 {@code OPENAI_API_KEY}，真实模型链路一旦被触发就必然超时/401，
 * 使得对话、工具调用、RAG 注入、token 计量、SSE 推送这些**核心业务**全部落在测试盲区里。
 * 换成桩之后，这些链路可以在无网络、确定性的条件下端到端跑通。
 *
 * <p>实现方式：{@code @Primary} + 具体返回类型。
 * <ul>
 *   <li>{@code @Primary} 让 {@code AiServices} 装配时选中桩，而 {@code LangChain4jConfig}
 *       里的真实 Bean 仍会被创建（构造期不发网络请求，无副作用）</li>
 *   <li>Bean 方法声明**具体桩类型**而非接口，测试可按具体类型注入并对其编程（压脚本、读调用记录）</li>
 * </ul>
 *
 * <p>用法：集成测试基类上 {@code @Import(StubModelsConfig.class)}。
 */
@TestConfiguration
public class StubModelsConfig {

    @Bean
    @Primary
    public StubChatLanguageModel stubChatLanguageModel() {
        return new StubChatLanguageModel();
    }

    @Bean
    @Primary
    public StubStreamingChatLanguageModel stubStreamingChatLanguageModel() {
        return new StubStreamingChatLanguageModel();
    }

    @Bean
    @Primary
    public DeterministicEmbeddingModel deterministicEmbeddingModel() {
        return new DeterministicEmbeddingModel();
    }

    /**
     * 把 {@code MilvusConfig.embeddingStore()} 整个换成内存向量库
     *
     * <p>为什么不能只加一个 {@code @Primary}：Spring 会实例化**所有**非懒加载单例，
     * 被冷落的那个 {@code embeddingStore} 依然会被创建，而 {@code MilvusEmbeddingStore.builder().build()}
     * 会同步阻塞到 gRPC deadline（实测约 10 秒，日志为 DEADLINE_EXCEEDED）。
     * 把 host 指向必然被拒绝的端口也没用——gRPC 带 {@code wait_for_ready} 重试，照样等满超时。
     *
     * <p>因此只能用 {@link BeanFactoryPostProcessor} 在**实例化之前**把这个 Bean 定义替换掉：
     * 走的是官方提供的「上下文自定义」机制，不是反射或私有 API。替换后拿到的是
     * 货真价实的 {@link InMemoryEmbeddingStore}，向量写入/检索行为与 Milvus 不可用时的降级路径一致。
     *
     * <p>代价：「Milvus 不可用 → 降级」这条真实路径本身不再被本测试上下文覆盖
     *（它仍由 {@code local} profile 的启动流程与 {@code MigrationSchemaTest} 隐式覆盖）。
     */
    @Bean
    static BeanFactoryPostProcessor replaceMilvusWithInMemoryStore() {
        return beanFactory -> {
            if (!(beanFactory instanceof BeanDefinitionRegistry registry)
                    || !registry.containsBeanDefinition("embeddingStore")) {
                return;
            }
            registry.removeBeanDefinition("embeddingStore");
            registry.registerBeanDefinition("embeddingStore", BeanDefinitionBuilder
                    .genericBeanDefinition(EmbeddingStore.class, InMemoryEmbeddingStore<TextSegment>::new)
                    .setScope(BeanDefinition.SCOPE_SINGLETON)
                    .getBeanDefinition());
        };
    }
}
