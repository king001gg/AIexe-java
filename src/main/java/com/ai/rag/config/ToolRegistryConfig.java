package com.ai.rag.config;

import com.ai.rag.agent.tools.*;
import com.ai.rag.service.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具注册配置类
 *
 * 注册工具执行器，供 {@code ToolController} 手动执行工具使用。
 * 面向 AiServices 的 function calling 由 {@link com.ai.rag.agent.tools.AgentTools} 通过 {@code @Tool} 注解暴露。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ToolRegistryConfig {

    private final CalculatorTool calculatorTool;
    private final WeatherTool weatherTool;
    private final MathTool mathTool;
    private final SearchTool searchTool;
    private final DateTimeTool dateTimeTool;

    /**
     * 注册工具执行器
     */
    @Bean
    public Map<String, ToolExecutor> toolExecutors() {
        Map<String, ToolExecutor> executors = new HashMap<>();
        executors.put(calculatorTool.getName(), calculatorTool);
        executors.put(weatherTool.getName(), weatherTool);
        executors.put(mathTool.getName(), mathTool);
        executors.put(searchTool.getName(), searchTool);
        executors.put(dateTimeTool.getName(), dateTimeTool);

        log.info("Registered {} tools", executors.size());
        return executors;
    }
}
