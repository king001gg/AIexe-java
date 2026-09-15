package com.ai.rag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流过滤器测试
 *
 * <p>验证突发容量耗尽后返回 429 + Retry-After，以及不同调用方之间互不影响。
 */
class RateLimitFilterTest {

    private static final String API_KEY_HEADER = "X-API-Key";

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        ApiKeyProperties apiKeyProperties = new ApiKeyProperties();
        RateLimitProperties rateLimitProperties = new RateLimitProperties();
        // 1 个/秒补充、突发容量 2：连续 3 次请求必定触发第 3 次拒绝
        rateLimitProperties.setRequestsPerMinute(60);
        rateLimitProperties.setBurst(2);

        filter = new RateLimitFilter(rateLimitProperties, apiKeyProperties,
                new ObjectMapper().findAndRegisterModules());
    }

    private static MockHttpServletRequest request(String apiKey, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/chat/message");
        if (apiKey != null) {
            request.addHeader(API_KEY_HEADER, apiKey);
        }
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private MockHttpServletResponse exchange(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    @DisplayName("突发容量内的请求放行，超出后返回 429 且带 Retry-After")
    void rejectsAfterBurstExhausted() throws Exception {
        assertThat(exchange(request("key-a", "10.0.0.1")).getStatus()).isEqualTo(200);
        assertThat(exchange(request("key-a", "10.0.0.1")).getStatus()).isEqualTo(200);

        MockHttpServletResponse rejected = exchange(request("key-a", "10.0.0.1"));

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(rejected.getContentAsString())
                .contains("请求过于频繁")
                .contains("429")
                .contains("/chat/message");
        assertThat(rejected.getContentType()).contains("application/json");
    }

    @Test
    @DisplayName("不同 API Key 各自独立计数，互不挤占配额")
    void bucketsAreIsolatedPerApiKey() throws Exception {
        assertThat(exchange(request("key-a", "10.0.0.1")).getStatus()).isEqualTo(200);
        assertThat(exchange(request("key-a", "10.0.0.1")).getStatus()).isEqualTo(200);
        assertThat(exchange(request("key-a", "10.0.0.1")).getStatus()).isEqualTo(429);

        // key-b 未消耗过配额，仍应放行
        assertThat(exchange(request("key-b", "10.0.0.1")).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("无密钥时按来源 IP 分桶")
    void fallsBackToRemoteAddress() throws Exception {
        assertThat(exchange(request(null, "10.0.0.1")).getStatus()).isEqualTo(200);
        assertThat(exchange(request(null, "10.0.0.1")).getStatus()).isEqualTo(200);
        assertThat(exchange(request(null, "10.0.0.1")).getStatus()).isEqualTo(429);

        // 另一个来源 IP 不受影响
        assertThat(exchange(request(null, "10.0.0.2")).getStatus()).isEqualTo(200);
    }
}
