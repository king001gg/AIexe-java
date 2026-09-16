package com.ai.rag.controller;

import com.ai.rag.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工具执行端点的请求契约（缺陷 D9）
 *
 * <p>缺陷本体：{@code ToolController} 原先用
 * {@code request.getOrDefault("arguments", "").toString()} 取参数，于是
 * <b>字段漏传</b>（{@code {}}）和 <b>字段名写错</b>（{@code {"input": ...}}）都会静默退化成
 * 「用空字符串执行工具」，再以 {@code success: true} 返回。调用方拿到的响应与
 * 「工具真的执行了并返回这个结果」完全无法区分——排查时会被引向错误的怀疑方向。
 *
 * <p>本测试锁定的口径是「<b>字段必须存在，值可为空</b>」：
 * <ul>
 *   <li>缺字段 / 写错名 / 空 body → 400（而不是 200）</li>
 *   <li>字段值不是字符串 → 400</li>
 *   <li>{@code ""} 与 {@code null} → 200 且正常执行（空参数对 {@code datetime} 是合法调用）</li>
 * </ul>
 *
 * <p>最后两条用例是**回归保护**：修复不能把正常调用一起挡掉——那只是把「静默成功」
 * 换成了「静默失败」，同样糟糕。
 */
class ToolEndpointContractTest extends IntegrationTestSupport {

    private static final String DATETIME_EXECUTE = "/tools/datetime/execute";
    private static final String CALCULATOR_EXECUTE = "/tools/calculator/execute";

    // ------------------------------------------------------------------
    // 一、请求形状有问题：必须 400，不能静默成功
    // ------------------------------------------------------------------

    @Test
    @DisplayName("D9：缺 arguments 字段（空对象）→ 400，而不是用空串执行并返回 success:true")
    void missingArgumentsFieldIsRejected() throws Exception {
        mockMvc.perform(apiPost(DATETIME_EXECUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("缺少必填字段 arguments"));
    }

    @Test
    @DisplayName("D9：字段名写错（input 而非 arguments）→ 400，不再静默成功")
    void misspelledArgumentsFieldIsRejected() throws Exception {
        mockMvc.perform(apiPost(CALCULATOR_EXECUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"1+2\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("缺少必填字段 arguments"));
    }

    @Test
    @DisplayName("D9：arguments 不是字符串（数字/对象）→ 400，而不是 toString() 后拿去执行")
    void nonStringArgumentsIsRejected() throws Exception {
        mockMvc.perform(apiPost(CALCULATOR_EXECUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arguments\":123}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("字段 arguments 必须是字符串"));

        mockMvc.perform(apiPost(CALCULATOR_EXECUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arguments\":{\"expression\":\"1+2\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("字段 arguments 必须是字符串"));
    }

    @Test
    @DisplayName("D9：完全没有 body → 400（required=false 让空 body 也走本控制器的错误体，而不是全局 ApiError 形状）")
    void emptyBodyIsRejectedWithControllerErrorShape() throws Exception {
        mockMvc.perform(apiPost(DATETIME_EXECUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("缺少必填字段 arguments"));
    }

    // ------------------------------------------------------------------
    // 二、空值仍然合法：不能把功能一起砍掉
    // ------------------------------------------------------------------

    @Test
    @DisplayName("D9：arguments 为空串 → 200 且真的返回当前时间（datetime 的空参数是合法调用）")
    void emptyStringArgumentsStillExecutes() throws Exception {
        mockMvc.perform(apiPost(DATETIME_EXECUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arguments\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.toolName").value("datetime"))
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("当前日期时间信息")))
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString(
                        String.valueOf(LocalDate.now().getYear()))));
    }

    @Test
    @DisplayName("D9：arguments 为 null → 按「没有参数」处理，同样 200")
    void nullArgumentsIsTreatedAsEmpty() throws Exception {
        mockMvc.perform(apiPost(DATETIME_EXECUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arguments\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("当前日期时间信息")));
    }

    // ------------------------------------------------------------------
    // 三、回归：正常调用与既有错误分支都不受影响
    // ------------------------------------------------------------------

    @Test
    @DisplayName("回归：正常传参的工具调用照旧 200，结果正确")
    void normalInvocationStillWorks() throws Exception {
        mockMvc.perform(apiPost(CALCULATOR_EXECUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arguments\":\"1+2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.toolName").value("calculator"))
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("3.0")));
    }

    @Test
    @DisplayName("回归：未知工具仍是 400，且错误体形状与新增的两个 400 一致")
    void unknownToolStillReturnsBadRequest() throws Exception {
        mockMvc.perform(apiPost("/tools/no-such-tool/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arguments\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("工具不存在：no-such-tool"));
    }

    @Test
    @DisplayName("回归：GET /tools/{name} 未知工具仍是 404（与执行端点的 400 区分开）")
    void unknownToolInfoStillReturnsNotFound() throws Exception {
        mockMvc.perform(apiGet("/tools/no-such-tool"))
                .andExpect(status().isNotFound());
    }
}
