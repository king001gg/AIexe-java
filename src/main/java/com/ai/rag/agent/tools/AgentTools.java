package com.ai.rag.agent.tools;

import com.ai.rag.model.entity.ToolCall;
import com.ai.rag.repository.ConversationRepository;
import com.ai.rag.repository.ToolCallRepository;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 工具注册（LangChain4j function calling）
 *
 * 将现有 {@code ToolExecutor} 实现以 {@link Tool} 注解暴露给 AiServices，
 * 由大模型自动选择并调用（替代手写的关键词匹配 ReAct 循环）。
 *
 * Stage 1 接通：每次工具调用写入 {@link ToolCallRepository}（tool_calls 表），
 * 通过 {@link ToolMemoryId} 注入当前会话 {@code sessionId}（该参数对大模型不可见）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTools {

    private final CalculatorTool calculatorTool;
    private final WeatherTool weatherTool;
    private final MathTool mathTool;
    private final SearchTool searchTool;
    private final DateTimeTool dateTimeTool;
    private final ToolCallRepository toolCallRepository;
    private final ConversationRepository conversationRepository;

    @Tool("计算算术表达式，支持四则运算、乘方、括号，如 1+2*3")
    public String calculator(@P("算术表达式") String expression, @ToolMemoryId String sessionId) {
        String input = expression == null ? "" : expression;
        return executeLogged("calculator", input, sessionId, () -> calculatorTool.execute(input));
    }

    @Tool("查询指定城市的天气信息")
    public String weather(@P("城市名称") String city, @ToolMemoryId String sessionId) {
        String input = city == null ? "" : city;
        return executeLogged("weather", input, sessionId, () -> weatherTool.execute(input));
    }

    @Tool("执行高级数学函数，如 sin/cos/tan/log/sqrt 等")
    public String math(@P("函数表达式，如 sin(1)") String expression, @ToolMemoryId String sessionId) {
        String input = expression == null ? "" : expression;
        return executeLogged("math", input, sessionId, () -> mathTool.execute(input));
    }

    @Tool("检索私有知识库中的相关内容")
    public String search(@P("查询关键词") String query, @ToolMemoryId String sessionId) {
        String input = query == null ? "" : query;
        return executeLogged("search", input, sessionId, () -> searchTool.execute(input));
    }

    @Tool("查询当前日期、时间、星期等信息")
    public String datetime(@P("查询内容，如 date/time/week，可为空") String query, @ToolMemoryId String sessionId) {
        String input = query == null ? "" : query;
        return executeLogged("datetime", input, sessionId, () -> dateTimeTool.execute(input));
    }

    /**
     * 执行工具并落库记录
     */
    private String executeLogged(String toolName, String input, String sessionId, Supplier<String> tool) {
        try {
            String output = tool.get();
            recordToolCall(toolName, input, output, sessionId, null);
            return output;
        } catch (RuntimeException e) {
            recordToolCall(toolName, input, null, sessionId, e);
            throw e;
        }
    }

    /**
     * 写入 tool_calls 表（失败时仅告警，不影响主流程）
     */
    private void recordToolCall(String toolName, String input, String output, String sessionId, Throwable error) {
        try {
            ToolCall toolCall = new ToolCall();
            toolCall.setToolName(toolName);
            toolCall.setToolInput(input);
            toolCall.setToolOutput(output);
            toolCall.setSessionId(sessionId);
            if (error != null) {
                toolCall.setStatus(ToolCall.Status.FAILED);
                toolCall.setErrorMessage(error.getMessage());
            } else {
                toolCall.setStatus(ToolCall.Status.SUCCESS);
            }
            conversationRepository.findBySessionId(sessionId)
                    .ifPresent(c -> toolCall.setConversationId(c.getId()));
            toolCallRepository.save(toolCall);
        } catch (Exception logEx) {
            log.warn("Failed to record tool call [{}]: {}", toolName, logEx.getMessage());
        }
    }
}
