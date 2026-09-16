package com.ai.rag.controller;

import com.ai.rag.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内容协商契约测试（缺陷 D10 的回归防护）
 *
 * <p><b>背景：</b>{@code milvus-sdk-java} → {@code azure-storage-blob} 传递依赖带进了
 * {@code jackson-dataformat-xml}。Spring Boot 一旦在 classpath 上探测到它，就会自动注册
 * {@code MappingJackson2XmlHttpMessageConverter}，而它支持的 {@code application/*+xml} 通配
 * 恰好匹配浏览器 {@code Accept} 头里的 {@code application/xhtml+xml}。
 * 于是**浏览器打开任何一个接口，拿到的都是 XML——包括成功响应**：
 *
 * <pre>
 * GET /api/tools   Accept: 浏览器默认      → 200 application/xhtml+xml
 *                  &lt;List&gt;&lt;item&gt;calculator&lt;/item&gt;...&lt;/List&gt;
 * GET /api/tools   Accept: application/json → 200 application/json
 *                  ["calculator","search",...]
 * </pre>
 *
 * <p>危害在于**测试与真实客户端之间差了这条缝**：MockMvc 默认不带 {@code Accept}，
 * 永远落在 JSON 分支上，所以 151 个用例全绿也发现不了。
 * 本类的作用就是显式带上真实浏览器的 Accept 头，把这个缝堵上。
 *
 * <p><b>注意：{@code spring.mvc.contentnegotiation.default-content-type=application/json}
 * 修不了这个问题。</b>该配置只在请求未显式声明可接受类型（Accept 为通配符或整个缺失）时生效，
 * 而浏览器是**显式**列出 {@code application/xhtml+xml} 的，
 * XML 转换器属于「被主动选中」而非「兜底命中」，默认类型配置在那条路径上根本不参与决策。
 * 真正的修法是让它不存在——即 {@code pom.xml} 里排除 {@code jackson-dataformat-xml}。
 */
class ContentNegotiationTest extends IntegrationTestSupport {

    /** 真实 Chrome 的 Accept 头 —— 关键是把 application/xhtml+xml 排在 *\/* 之前 */
    private static final String BROWSER_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";

    @Test
    @DisplayName("D10：浏览器 Accept 请求成功端点，响应仍是 JSON（而非 XML）")
    void browserAcceptStillGetsJsonOnSuccess() throws Exception {
        mockMvc.perform(apiGet("/tools")
                        .header(HttpHeaders.ACCEPT, BROWSER_ACCEPT))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@=='calculator')]").exists());
    }

    @Test
    @DisplayName("D10：浏览器 Accept 请求错误端点，响应体仍是 JSON 格式的 ApiError")
    void browserAcceptStillGetsJsonOnError() throws Exception {
        mockMvc.perform(apiGet("/definitely-not-exists")
                        .header(HttpHeaders.ACCEPT, BROWSER_ACCEPT))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("请求的资源不存在"));
    }

    @Test
    @DisplayName("D10：显式声明只要 XML 的客户端得到干净的 406，而不是静默返回 XML")
    void explicitXmlAcceptIsRejectedAsNotAcceptable() throws Exception {
        // 本服务只产出 JSON（与 SSE），XML 转换器已随依赖排除而不存在。
        // 因此「只接受 XML」的请求得到 406 Not Acceptable —— 这是正确的 HTTP 语义：
        // 宁可明确拒绝，也不要像修复前那样悄悄换一种格式返回给客户端。
        mockMvc.perform(apiGet("/tools")
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_XML_VALUE))
                .andExpect(status().isNotAcceptable());
    }
}
