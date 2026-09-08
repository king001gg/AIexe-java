package com.ai.rag.service;

import com.ai.rag.model.dto.ChatRequest;
import com.ai.rag.model.dto.ChatResponse;
import com.ai.rag.util.ContextWindowManager;
import com.ai.rag.util.TokenCounter;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Agent核心服务
 * 实现ReAct模式（Thought→Action→Observation）的Agent循环
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final ChatLanguageModel chatLanguageModel;
    private final Map<String, ToolExecutor> toolExecutors;
    private final ContextWindowManager contextWindowManager;

    @Value("${agent.max-iterations:5}")
    private int maxIterations;

    /**
     * 处理用户请求
     */
    @Transactional
    public ChatResponse processRequest(ChatRequest request) {
        log.info("Processing chat request for session: {}", request.getSessionId());

        try {
            // 1. 添加用户消息到上下文窗口
            contextWindowManager.addMessage(request.getSessionId(), "user", request.getMessage());

            // 2. 构建上下文
            String context = buildContext(request.getSessionId());

            // 3. 执行ReAct循环
            String response = executeReactLoop(request, context, 0);

            // 4. 添加助手响应到上下文窗口
            contextWindowManager.addMessage(request.getSessionId(), "assistant", response);

            // 5. 构建响应
            return buildResponse(response, request);

        } catch (Exception e) {
            log.error("Error processing chat request", e);
            throw new RuntimeException("Failed to process request", e);
        }
    }

    /**
     * 构建上下文
     */
    private String buildContext(String sessionId) {
        List<ContextWindowManager.ConversationContext.Message> context =
            contextWindowManager.getContext(sessionId);

        StringBuilder sb = new StringBuilder();
        for (ContextWindowManager.ConversationContext.Message msg : context) {
            sb.append(msg.role).append(": ").append(msg.content).append("\n");
        }
        return sb.toString();
    }

    /**
     * 执行ReAct循环
     * 实现 Thought → Action → Observation 的循环
     */
    private String executeReactLoop(ChatRequest request, String context, int iteration) {
        if (iteration >= maxIterations) {
            log.warn("Max iterations reached, generating final response");
            return generateFinalResponse(request, context);
        }

        // 1. Thought - 思考步骤
        String thought = think(request.getMessage(), context);
        log.info("Thought {}: {}", iteration + 1, thought);

        // 2. Action - 判断是否需要调用工具
        if (request.getUseTools() != null && request.getUseTools() && needsTool(thought)) {
            String toolName = extractToolName(thought);
            String toolInput = extractToolInput(thought);

            if (toolName != null && toolExecutors.containsKey(toolName)) {
                // 3. Observation - 执行工具并观察结果
                ToolExecutor executor = toolExecutors.get(toolName);
                String observation = executor.execute(toolInput);
                log.info("Observation: {}", observation);

                // 将观察结果加入上下文，继续循环
                String enrichedContext = context + "\n工具观察结果: " + observation + "\n";
                return executeReactLoop(request, enrichedContext, iteration + 1);
            }
        }

        // 4. 生成最终响应
        return generateFinalResponse(request, context);
    }

    /**
     * 思考步骤
     */
    private String think(String query, String context) {
        String prompt = String.format(
            "你是一个智能助手。请思考如何回答用户的问题。\n\n" +
            "上下文：\n%s\n\n" +
            "用户问题：%s\n\n" +
            "如果需要调用工具（如计算、查询天气、检索知识库等），请说明要调用哪个工具和参数；否则直接说明你的思考结果。",
            context, query
        );

        try {
            return chatLanguageModel.generate(prompt);
        } catch (Exception e) {
            log.warn("Error in thinking step, using fallback", e);
            return "直接回答";
        }
    }

    /**
     * 判断是否需要调用工具
     */
    private boolean needsTool(String thought) {
        String lower = thought.toLowerCase();
        return lower.contains("调用工具") ||
               lower.contains("tool") ||
               lower.contains("计算") ||
               lower.contains("天气") ||
               lower.contains("检索") ||
               lower.contains("查询");
    }

    /**
     * 提取工具名称
     */
    private String extractToolName(String thought) {
        if (thought.contains("计算") || thought.contains("calculator")) {
            return "calculator";
        } else if (thought.contains("天气") || thought.contains("weather")) {
            return "weather";
        } else if (thought.contains("数学") || thought.contains("math")) {
            return "math";
        } else if (thought.contains("检索") || thought.contains("search") || thought.contains("知识库")) {
            return "search";
        } else if (thought.contains("日期") || thought.contains("时间") || thought.contains("datetime")) {
            return "datetime";
        }
        return null;
    }

    /**
     * 提取工具输入
     */
    private String extractToolInput(String thought) {
        // 简单提取：返回思考中的关键内容
        return thought;
    }

    /**
     * 生成最终响应
     */
    private String generateFinalResponse(ChatRequest request, String context) {
        String prompt = String.format(
            "你是一个智能助手，请根据以下信息回答用户的问题。\n\n" +
            "上下文：\n%s\n\n" +
            "用户问题：%s\n\n" +
            "请直接给出清晰、准确的回答。",
            context, request.getMessage()
        );

        try {
            return chatLanguageModel.generate(prompt);
        } catch (Exception e) {
            log.error("Error generating final response", e);
            return "抱歉，我暂时无法回答这个问题。";
        }
    }

    /**
     * 构建响应
     */
    private ChatResponse buildResponse(String response, ChatRequest request) {
        ContextWindowManager.TokenUsage tokenUsage =
            contextWindowManager.getTokenUsage(request.getSessionId());

        ChatResponse chatResponse = new ChatResponse();
        chatResponse.setSessionId(request.getSessionId());
        chatResponse.setResponse(response);
        chatResponse.setInputTokens(tokenUsage.inputTokens);
        chatResponse.setOutputTokens(tokenUsage.outputTokens);
        chatResponse.setTotalTokens(tokenUsage.totalTokens);
        chatResponse.setCost(TokenCounter.calculateCost(
            tokenUsage.inputTokens, tokenUsage.outputTokens, "gpt-4"));
        chatResponse.setTimestamp(java.time.LocalDateTime.now());
        return chatResponse;
    }
}