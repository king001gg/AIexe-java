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
     */
    @PostMapping("/{toolName}/execute")
    public ResponseEntity<Map<String, Object>> executeTool(
            @PathVariable String toolName,
            @RequestBody Map<String, Object> request) {
        log.info("Executing tool: {} with request: {}", toolName, request);

        ToolExecutor executor = toolExecutors.get(toolName);
        if (executor == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "工具不存在：" + toolName);
            return ResponseEntity.badRequest().body(response);
        }

        try {
            String arguments = request.getOrDefault("arguments", "").toString();
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
}