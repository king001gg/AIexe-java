package com.ai.rag.controller;

import com.ai.rag.service.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/tools")
@RequiredArgsConstructor
public class ToolController {

    private final Map<String, ToolExecutor> toolExecutors;

    /**
     * 获取所有可用工具
     */
    @GetMapping
    public ResponseEntity<List<String>> getAllTools() {
        return ResponseEntity.ok(toolExecutors.keySet().stream().toList());
    }

    /**
     * 执行工具
     *
     * <p><b>为什么 {@code arguments} 必须显式存在（缺陷 D9）：</b>
     * 原先是 {@code request.getOrDefault("arguments", "").toString()} —— 字段名写错
     * （如 {@code input}）或整个漏传（{@code {}}）都会静默退化成「用空串执行」，
     * 再以 {@code success: true} 返回。调用方无法分辨「工具真的执行了」与「请求写错了」。
     *
     * <p>现在的口径是<b>字段必须存在，值可为空</b>：
     * <ul>
     *   <li>缺字段 / 字段名写错 → 400</li>
     *   <li>{@code arguments} 不是字符串（数字、对象…）→ 400</li>
     *   <li>{@code {"arguments": ""}} 或 {@code {"arguments": null}} → 200 正常执行</li>
     * </ul>
     * 空串必须放行：{@link com.ai.rag.agent.tools.DateTimeTool} 对空参数是**合法**调用
     * （返回当前日期时间），工具描述里就写着「可为空」。写成 {@code isBlank()} 校验会砍掉这个功能。
     *
     * <p>错误体分两档：**请求形状问题**（400）返回 {@code {success, message}}，
     * 与「工具不存在」保持同形；**执行失败**（500）额外带 {@code toolName}，便于定位。
     */
    @PostMapping("/{toolName}/execute")
    public ResponseEntity<Map<String, Object>> executeTool(
            @PathVariable String toolName,
            // required = false 是为了让「空 body」也走本方法的 400 分支，
            // 而不是先被 HttpMessageNotReadableException 拦成全局 ApiError 形状。
            @RequestBody(required = false) Map<String, Object> request) {
        log.info("Executing tool: {} with request: {}", toolName, request);

        ToolExecutor executor = toolExecutors.get(toolName);
        if (executor == null) {
            return badRequest("工具不存在：" + toolName);
        }

        Map<String, Object> body = request == null ? Map.of() : request;
        if (!body.containsKey("arguments")) {
            return badRequest("缺少必填字段 arguments");
        }

        Object rawArguments = body.get("arguments");
        if (rawArguments != null && !(rawArguments instanceof String)) {
            return badRequest("字段 arguments 必须是字符串");
        }

        try {
            // null 与 "" 等价：字段在，就是「没有参数」
            String arguments = rawArguments == null ? "" : (String) rawArguments;
            String result = executor.execute(arguments);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("toolName", toolName);
            response.put("result", result);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error executing tool: {}", toolName, e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("toolName", toolName);
            response.put("message", "工具执行失败：" + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 获取工具信息
     */
    @GetMapping("/{toolName}")
    public ResponseEntity<Map<String, Object>> getToolInfo(@PathVariable String toolName) {
        ToolExecutor executor = toolExecutors.get(toolName);
        if (executor == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("name", executor.getName());
        response.put("available", true);

        return ResponseEntity.ok(response);
    }

    /**
     * 统一的「请求有问题」响应体（400）：只有 {@code success} 与 {@code message}，
     * 与既有的「工具不存在」保持同形，调用方只需读 {@code success} 判成败。
     */
    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return ResponseEntity.badRequest().body(response);
    }
}