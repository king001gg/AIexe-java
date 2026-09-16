package com.ai.rag.security;

import com.ai.rag.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 限流端到端测试（走完整的过滤器链）
 *
 * <p>与 {@code RateLimitFilterTest}（直接调用过滤器）的分工：那里验证令牌桶算法与报文格式，
 * 这里验证**装配**——过滤器有没有真的注册进 Servlet 链、执行顺序是否正确
 * （限流必须在安全认证之前，否则攻击者可以用无效密钥无限刷接口）。
 *
 * <p>限流参数故意调成 {@code burst=2}：桶容量即突发容量，所以「第 3 个请求必定被拒」，
 * 断言不依赖计时，不会出现偶发失败。
 *
 * <p>每个用例使用**不同的密钥值**：限流按调用方分桶，用不同密钥就拿到独立的新桶，
 * 用例之间不会互相耗尽令牌。注意分桶键取自请求头原文（有效与否不论），
 * 因此任意随机串都能拿到独立桶。
 */
@SpringBootTest(properties = {
        "rag.retrieval.min-score=0.55",
        "security.enabled=true",
        "security.api-keys[0].name=rate-limit-client",
        "security.api-keys[0].key=rate-limit-valid-key",
        "rate-limit.enabled=true",
        "rate-limit.requests-per-minute=60",
        "rate-limit.burst=2"
})
@ActiveProfiles("test")
@Import(com.ai.rag.support.StubModelsConfig.class)
class RateLimitIntegrationTest extends IntegrationTestSupport {

    private static final String PROBE = "/chat/context/rate-limit-probe";

    @Test
    @DisplayName("突发额度用尽后第 3 个请求返回 429，带 Retry-After 与统一错误体")
    void exceedingBurstReturnsTooManyRequests() throws Exception {
        String caller = freshKey();

        expectNot429(caller);
        expectNot429(caller);

        mockMvc.perform(apiGet(PROBE).header(API_KEY_HEADER, caller))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value("请求过于频繁"))
                .andExpect(jsonPath("$.detail").value(containsString("60 次/分钟")));
    }

    @Test
    @DisplayName("超限响应体不得回显调用方的密钥（避免密钥经由错误信息外泄）")
    void rejectionBodyDoesNotEchoTheKey() throws Exception {
        String caller = freshKey();
        expectNot429(caller);
        expectNot429(caller);

        String body = mockMvc.perform(apiGet(PROBE).header(API_KEY_HEADER, caller))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body)
                .doesNotContain(caller)
                .doesNotContain("rate-limit-valid-key");
    }

    @Test
    @DisplayName("分桶隔离：一个调用方被限流不影响另一个调用方")
    void bucketsAreIsolatedPerCaller() throws Exception {
        String exhausted = freshKey();
        expectNot429(exhausted);
        expectNot429(exhausted);
        mockMvc.perform(apiGet(PROBE).header(API_KEY_HEADER, exhausted))
                .andExpect(status().isTooManyRequests());

        String other = freshKey();
        expectNot429(other);
        expectNot429(other);
    }

    @Test
    @DisplayName("限流先于认证：无效密钥同样被限流，不能靠瞎猜密钥绕过限流")
    void rateLimitAppliesBeforeAuthentication() throws Exception {
        String bogus = "bogus-" + UUID.randomUUID();

        expectNot429(bogus);
        expectNot429(bogus);

        mockMvc.perform(apiGet(PROBE).header(API_KEY_HEADER, bogus))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("X-Forwarded-For 不可伪造：换一个转发头不能拿到新桶")
    void forwardedForHeaderCannotBypassRateLimit() throws Exception {
        // 不携带密钥 → 退化到按来源 IP 分桶。反复用不同的 X-Forwarded-For 试图刷桶，
        // 若实现采信了该头，每个请求都会是新桶、永远拿不到 429。
        boolean sawTooManyRequests = false;
        for (int i = 0; i < 6 && !sawTooManyRequests; i++) {
            int status = mockMvc.perform(apiGet(PROBE)
                            .header("X-Forwarded-For", "203.0.113." + i))
                    .andReturn().getResponse().getStatus();
            sawTooManyRequests = status == 429;
        }

        assertThat(sawTooManyRequests)
                .as("伪造 X-Forwarded-For 不应绕过按 IP 的限流")
                .isTrue();
    }

    @Test
    @DisplayName("被限流的请求不会打到控制器：不应产生任何业务副作用")
    void rejectedRequestsNeverReachController() throws Exception {
        String caller = freshKey();
        // 该端点只读，用 message 计数这类副作用不好观察；
        // 改为证明「被拒的请求返回的是限流报文，而不是业务响应」
        expectNot429(caller);
        expectNot429(caller);

        String body = mockMvc.perform(apiGet(PROBE).header(API_KEY_HEADER, caller))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body).doesNotContain("sessionId");
    }

    private String freshKey() {
        return "caller-" + UUID.randomUUID();
    }

    private void expectNot429(String caller) throws Exception {
        int status = mockMvc.perform(apiGet(PROBE).header(API_KEY_HEADER, caller))
                .andReturn().getResponse().getStatus();

        assertThat(status)
                .as("调用方 %s 在突发额度内不应被限流", caller)
                .isNotEqualTo(429);
    }

    private static final String API_KEY_HEADER = "X-API-Key";
}
