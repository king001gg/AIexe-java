package com.ai.rag.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 根路径索引
 *
 * <p>访问 {@code http://localhost:8080/api/} 时给出一份可用的端点清单。
 *
 * <p>加这个是因为上下文根路径此前**没有任何映射**：浏览器打开它只会得到
 * 一个 404（修复 D1 之前更糟，是 500 + 一句 {@code No static resource .}）。
 * 调试的人第一反应就是开根路径，拿到 404 很容易误判成「服务没起来」。
 *
 * <p>这里刻意只返回 JSON 而不是 HTML 页面：本服务是纯 API，
 * 一个 HTML 落地页会暗示它有人机界面，反而误导。
 */
@RestController
public class ApiIndexController {

    @Value("${spring.application.name:ai-rag-agent}")
    private String applicationName;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @GetMapping(value = "/", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> index() {
        String base = contextPath;

        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("GET  " + base + "/tools", "已注册的工具列表");
        endpoints.put("POST " + base + "/tools/{name}/execute", "执行工具，body: {\"arguments\":\"...\"}");
        endpoints.put("POST " + base + "/chat/message", "对话，body: {\"sessionId\":\"...\",\"message\":\"...\"}");
        endpoints.put("POST " + base + "/chat/stream", "SSE 流式对话（text/event-stream）");
        endpoints.put("GET  " + base + "/chat/context/{sessionId}", "查看会话上下文");
        endpoints.put("DEL  " + base + "/chat/context/{sessionId}", "清空会话上下文");
        endpoints.put("GET  " + base + "/chat/tokens/{sessionId}", "会话 token 用量");
        endpoints.put("POST " + base + "/documents/upload", "上传文档（multipart: file, knowledgeBaseName）");
        endpoints.put("GET  " + base + "/documents/knowledge-bases", "知识库列表");
        endpoints.put("GET  " + base + "/documents/knowledge-bases/{id}/documents", "知识库下的文档分块");
        endpoints.put("GET  " + base + "/documents/search?query=", "检索知识库");
        endpoints.put("GET  " + base + "/documents/search/detailed?query=", "检索（含分数与各路排名）");
        endpoints.put("POST " + base + "/memory/save", "写入长期记忆");
        endpoints.put("GET  " + base + "/memory/{sessionId}/{key}", "读取长期记忆");
        endpoints.put("POST " + base + "/evaluation/retrieval", "跑检索评测集");
        endpoints.put("GET  " + base + "/actuator/health", "健康检查");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("application", applicationName);
        body.put("message", "AI RAG 问答 Agent 已启动。以下为可用端点，全部返回 JSON。");
        body.put("note", "对话类接口需要可用的 OPENAI_API_KEY；上传与检索类接口不依赖大模型。");
        body.put("endpoints", endpoints);
        return body;
    }
}
