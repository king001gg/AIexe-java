package com.ai.rag.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API Key 认证：走完整安全过滤链的集成验证
 *
 * <p>区别于此前的 {@code ApiKeyPropertiesTest}（只测密钥比对的纯逻辑），
 * 这里验证的是**过滤器链的装配**：白名单是否真的放行、受保护路径是否真的拦住、
 * 401 的响应体是否符合约定的 {@code ApiError} 结构。
 * 「配置写对了但对不上」这类问题只有走完整链条才暴露得出来（历史上就踩过：
 * 字符串版 {@code requestMatchers} 走 MVC 匹配器，匹配不上 actuator 端点）。
 */
class ApiKeySecurityIntegrationTest extends SecuredApiTestSupport {

    private static final String PROTECTED_PATH = "/chat/context/security-probe";

    @Test
    @DisplayName("无密钥访问受保护端点 → 401，且响应体是约定的 ApiError JSON")
    void missingKeyIsRejectedWithApiErrorBody() throws Exception {
        mockMvc.perform(apiGet(PROTECTED_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("未认证"))
                .andExpect(jsonPath("$.detail").value(containsString(API_KEY_HEADER)))
                .andExpect(jsonPath("$.path").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("错误密钥 → 401（与无密钥不可区分，不泄露「密钥存在但不对」）")
    void wrongKeyIsRejected() throws Exception {
        mockMvc.perform(apiGet(PROTECTED_PATH).header(API_KEY_HEADER, "wrong-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("密钥为空串/仅空格 → 401，不能被当成有效密钥")
    void blankKeyIsRejected() throws Exception {
        mockMvc.perform(apiGet(PROTECTED_PATH).header(API_KEY_HEADER, ""))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(apiGet(PROTECTED_PATH).header(API_KEY_HEADER, "   "))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("正确密钥 → 放行到控制器（401 消失即证明认证通过）")
    void validKeyReachesController() throws Exception {
        mockMvc.perform(apiGet(PROTECTED_PATH).header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("security-probe"))
                .andExpect(jsonPath("$.messageCount").value(0));
    }

    @Test
    @DisplayName("受保护的写接口同样要求认证：POST /chat/message 无密钥 → 401")
    void protectedPostRequiresKey() throws Exception {
        mockMvc.perform(apiPost("/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("白名单：/actuator/health 无密钥可直接访问（拿到的是 actuator 自己的响应，不是 401）")
    void healthEndpointIsPermittedWithoutKey() throws Exception {
        // 本机没有 Redis/MySQL，health 会返回 503——重点是「不是 401」，
        // 即请求确实抵达了 actuator，而不是被安全链拦住。
        int status = mockMvc.perform(apiGet("/actuator/health"))
                .andReturn().getResponse().getStatus();

        assertThat(status)
                .as("白名单端点不应返回 401")
                .isNotEqualTo(401);
    }

    @Test
    @DisplayName("白名单：/actuator/info 无密钥可访问")
    void infoEndpointIsPermittedWithoutKey() throws Exception {
        int status = mockMvc.perform(apiGet("/actuator/info"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isNotEqualTo(401);
    }

    @Test
    @DisplayName("白名单是精确匹配，不是前缀放行：/actuator/metrics 仍需认证")
    void nonWhitelistedActuatorEndpointStillRequiresKey() throws Exception {
        mockMvc.perform(apiGet("/actuator/metrics"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(apiGet("/actuator/metrics").header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("白名单不能被路径穿越绕过：/actuator/health/../metrics 拿不到受保护数据")
    void whitelistCannotBeBypassedByPathTraversal() throws Exception {
        int status = mockMvc.perform(apiGet("/actuator/health/../metrics"))
                .andReturn().getResponse().getStatus();

        assertThat(status)
                .as("未认证的穿越路径请求不能成功（200），也不能返回受保护数据")
                .isNotEqualTo(200);
    }

    @Test
    @DisplayName("认证按请求独立判定：未携带密钥的后续请求不会蹭上一次的会话")
    void authenticationDoesNotLeakAcrossRequests() throws Exception {
        mockMvc.perform(apiGet(PROTECTED_PATH).header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk());

        mockMvc.perform(apiGet(PROTECTED_PATH))
                .andExpect(status().isUnauthorized());
    }
}
