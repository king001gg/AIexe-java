package com.ai.rag.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 集成测试基类：共享一个 Spring 上下文 + 桩模型 + 干净的库表
 *
 * <p>三个关键决定：
 *
 * <ol>
 *   <li><b>所有集成测试复用同一份注解与属性</b>，从而命中 Spring 的上下文缓存——
 *       否则每个测试类都要重跑一次完整的上下文启动（含 Flyway 迁移），测试会慢到没人愿意跑。</li>
 *
 *   <li><b>向量库走内存实现</b>（见 {@link StubModelsConfig#replaceMilvusWithInMemoryStore()}）：
 *       本机没有 Milvus，默认配置下 {@code MilvusEmbeddingStore} 会先阻塞约 10 秒的
 *       DEADLINE_EXCEEDED 才降级，每个测试上下文都要白付一次。</li>
 *
 *   <li><b>{@code rag.retrieval.min-score=0.55}</b>：这个值必须结合**缺陷 D3** 来理解。
 *       降级用的 {@code InMemoryEmbeddingStore} 返回的 score 是 {@code (cosine+1)/2}，
 *       余弦为 0 的完全无关文本 score 也是 0.5。因此阈值必须落在 0.5 与「有重合文本的得分」之间：
 *       0.55 恰好等价于「原始余弦 ≥ 0.1」，既挡住零重叠，又放行真实命中。
 *       用生产默认值 0.3 会等价于「余弦 ≥ -0.4」，等于不设阈值——「无关问题不注入知识」
 *       这类断言就完全失去意义。详见 {@code EmbeddingDiagTest} 与测试报告 D3。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "rag.retrieval.min-score=0.55",

        /*
         * 与生产（application-local.yml）对齐，关掉 Open Session in View。
         *
         * 缺陷 D8 的教训：4 个返回 JPA 实体的读端点会在序列化懒关联时抛
         * LazyInitializationException → 500。但测试一直没发现，因为测试上下文里
         * open-in-view 取了 Spring Boot 的**默认值 true** —— Session 被拖到视图渲染之后才关，
         * 懒加载「恰好」能工作，把生产上的必现故障掩盖成了测试里永远绿。
         *
         * 这正是报告第六节第 6 条说的「配置矩阵」问题：只在一种配置下测过。
         * 显式设为 false 后，任何「返回实体 + 序列化懒关联」的写法都会在这里直接失败。
         */
        "spring.jpa.open-in-view=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(StubModelsConfig.class)
public abstract class IntegrationTestSupport {

    /** 生产配置的 context-path，MockMvc 不会自动套用，请求时需显式带上 */
    protected static final String CONTEXT_PATH = "/api";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StubChatLanguageModel chatModel;

    @Autowired
    protected StubStreamingChatLanguageModel streamingChatModel;

    @Autowired
    protected DeterministicEmbeddingModel embeddingModel;

    @BeforeEach
    void resetSharedState() {
        chatModel.reset();
        streamingChatModel.reset();
        cleanDatabase();
    }

    /**
     * 清空业务表
     *
     * <p>H2 是 {@code DB_CLOSE_DELAY=-1} 的进程内库，同一上下文的多个测试类共享它，
     * 上一个用例留下的会话/消息会污染下一个用例的断言。按外键依赖倒序删除。
     */
    protected void cleanDatabase() {
        for (String table : new String[]{
                "messages", "tool_calls", "token_usage", "documents",
                "memories", "user_preferences", "knowledge_bases", "conversations"}) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }
    }

    /**
     * 构造一个带生产 context-path 的 GET 请求
     *
     * <p>MockMvc 是进程内调用，不经过 Servlet 容器，{@code server.servlet.context-path} 不会自动生效；
     * 只有同时把 contextPath 和 URI 前缀都设成 {@code /api}，Spring 才会把
     * 应用内路径解析为 {@code /chat/...}（控制器映射所在的路径）。
     * 只设 contextPath 不写前缀会被 MockMvc 直接拒绝：
     * {@code Request URI [/chat/...] does not start with context path [/api]}。
     *
     * @param path 相对 context-path 的路径，例如 {@code /chat/context/s1}
     */
    protected MockHttpServletRequestBuilder apiGet(String path) {
        return get(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    /**
     * 构造一个带生产 context-path 的 POST 请求
     *
     * @param path 相对 context-path 的路径，例如 {@code /chat/message}
     */
    protected MockHttpServletRequestBuilder apiPost(String path) {
        return post(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    /**
     * 构造一个带生产 context-path 的 DELETE 请求
     *
     * @param path 相对 context-path 的路径，例如 {@code /chat/context/s1}
     */
    protected MockHttpServletRequestBuilder apiDelete(String path) {
        return delete(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }
}
