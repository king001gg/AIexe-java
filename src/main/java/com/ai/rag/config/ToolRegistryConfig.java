package com.ai.rag.config;

import com.ai.rag.agent.tools.*;
import com.ai.rag.service.ToolExecutor;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册配置类
 * 注册所有工具执行器和工具规范
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

    /**
     * 注册工具规范
     */
    @Bean
    public Map<String, ToolSpecification> toolSpecifications() {
        Map<String, ToolSpecification> specifications = new HashMap<>();

        specifications.put("calculator", ToolSpecification.builder()
            .name("calculator")
            .description("计算数学表达式，支持四则运算、乘方、括号等")
            .build());

        specifications.put("weather", ToolSpecification.builder()
            .name("weather")
            .description("查询指定城市的天气信息")
            .build());

        specifications.put("math", ToolSpecification.builder()
            .name("math")
            .description("执行高级数学函数，如sin、cos、tan、log、sqrt等")
            .build());

        specifications.put("search", ToolSpecification.builder()
            .name("search")
            .description("检索私有知识库中的相关内容")
            .build());

        specifications.put("datetime", ToolSpecification.builder()
            .name("datetime")
            .description("查询当前日期、时间、星期等信息")
            .build());

        log.info("Registered {} tool specifications", specifications.size());
        return specifications;
    }
}