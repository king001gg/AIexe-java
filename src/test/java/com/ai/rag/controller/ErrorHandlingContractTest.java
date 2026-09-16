package com.ai.rag.controller;

import com.ai.rag.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 异常映射契约测试
 *
 * <p><b>本类曾用于固化缺陷 D1 / D2 的「快照」（characterization test），
 * 现已随缺陷修复翻转为正向契约断言。</b>历史背景：
 *
 * <ul>
 *   <li><b>D1</b>：{@code GlobalExceptionHandler} 的 {@code @ExceptionHandler(Exception.class)}
 *       兜底把 Spring MVC 的框架级异常（{@code HttpRequestMethodNotSupportedException} /
 *       {@code HttpMediaTypeNotSupportedException} / {@code NoResourceFoundException}）也一并接住，
 *       而这些异常本该由 {@code DefaultHandlerExceptionResolver} 映射为 405 / 415 / 404，
 *       结果是**客户端错误被降级成 500 服务端错误**。</li>
 *   <li><b>D2</b>：兜底分支把 {@code e.getMessage()} 原样回给客户端，
 *       404 时泄漏出「No static resource ...」这类 Spring 内部措辞。</li>
 * </ul>
 *
 * <p>修复方式见 {@code GlobalExceptionHandler}：为每类框架异常补显式处理器 + 兜底文案固定。
 * 若这些用例再次失败，说明有人把兜底的优先级或文案改回去了。
 */
class ErrorHandlingContractTest extends IntegrationTestSupport {

    private static final String POST_ENDPOINT = "/chat/message";

    // ---------- D1：框架级异常必须回到它本该有的状态码 ----------

    @Test
    @DisplayName("D1：GET 打到只支持 POST 的端点 → 405，且告知支持的方法")
    void methodNotAllowedReturnsMethodNotAllowed() throws Exception {
        mockMvc.perform(apiGet(POST_ENDPOINT))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.message").value("请求方法不支持"))
                .andExpect(jsonPath("$.detail").value(containsString("POST")));
    }

    @Test
    @DisplayName("D1：不支持的 Content-Type → 415")
    void unsupportedMediaTypeReturnsUnsupportedMediaType() throws Exception {
        mockMvc.perform(apiPost(POST_ENDPOINT)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("你好"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.message").value("请求体类型不支持"))
                .andExpect(jsonPath("$.detail").value(containsString("text/plain")));
    }

    @Test
    @DisplayName("D1：未声明 Content-Type → 415")
    void missingContentTypeReturnsUnsupportedMediaType() throws Exception {
        mockMvc.perform(apiPost(POST_ENDPOINT).content("{\"message\":\"你好\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    @DisplayName("D1：不存在的路径 → 404")
    void unknownPathReturnsNotFound() throws Exception {
        mockMvc.perform(apiGet("/definitely-not-exists"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("请求的资源不存在"));
    }

    @Test
    @DisplayName("D1：路径变量缺失（/chat/context/）→ 404")
    void missingPathVariableReturnsNotFound() throws Exception {
        mockMvc.perform(apiGet("/chat/context/"))
                .andExpect(status().isNotFound());
    }

    // ---------- D2：错误响应体不得泄漏内部实现 ----------

    @Test
    @DisplayName("D2：404 的 detail 不再泄漏「No static resource」这类 Spring 内部措辞")
    void notFoundDetailDoesNotLeakInternalWording() throws Exception {
        String detail = readDetail(apiGet("/definitely-not-exists"));

        assertThat(detail)
                .as("历史缺陷：曾回填 e.getMessage()，把静态资源兜底处理器的措辞吐给客户端")
                .doesNotContain("No static resource")
                .doesNotContain("NoResourceFoundException")
                .doesNotContain("org.springframework");
    }

    @Test
    @DisplayName("D2：500 兜底的 detail 是固定文案，不回填原始异常消息")
    void internalErrorDetailIsFixedWording() throws Exception {
        chatModel.failWith(new IllegalStateException(
                "connection refused to jdbc:mysql://prod-db:3306/ai_rag_db?user=root&password=s3cr3t"));

        String body = mockMvc.perform(apiPost(POST_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"session-err\",\"message\":\"你好\"}"))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("500 响应体不得包含数据库连接串、口令或异常类名等内部细节")
                .doesNotContain("jdbc:mysql")
                .doesNotContain("s3cr3t")
                .doesNotContain("IllegalStateException")
                .contains("服务处理请求时发生内部错误");
    }

    // ---------- 未被误伤的分支 ----------

    @Test
    @DisplayName("合法但不兼容的 JSON 类型 → 400（兜底没有误伤校验分支）")
    void wrongJsonTypeIsRejectedAsBadRequest() throws Exception {
        mockMvc.perform(apiPost(POST_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": [\"不\",\"是\",\"字符串\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求体格式错误"));
    }

    @Test
    @DisplayName("无论哪条异常分支，响应体都保持统一的 ApiError 字段集合")
    void errorBodyShapeIsConsistentAcrossBranches() throws Exception {
        for (var request : new org.springframework.test.web.servlet.RequestBuilder[]{
                apiGet(POST_ENDPOINT),
                apiPost(POST_ENDPOINT).contentType(MediaType.TEXT_PLAIN).content("x"),
                apiGet("/definitely-not-exists"),
                apiPost(POST_ENDPOINT).contentType(MediaType.APPLICATION_JSON).content("{")}) {
            var result = mockMvc.perform(request).andReturn();
            String body = result.getResponse().getContentAsString();

            assertThat(body)
                    .as("错误响应体应始终是 ApiError 结构，状态码 %s", result.getResponse().getStatus())
                    .contains("\"timestamp\"")
                    .contains("\"status\"")
                    .contains("\"message\"")
                    .contains("\"detail\"")
                    .contains("\"path\"");
        }
    }

    private String readDetail(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(
                mockMvc.perform(request).andReturn().getResponse().getContentAsString(), "$.detail");
    }
}
