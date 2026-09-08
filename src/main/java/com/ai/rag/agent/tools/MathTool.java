package com.ai.rag.agent.tools;

import com.ai.rag.service.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数学函数工具
 * 支持高级数学运算：三角函数、对数、指数等
 */
@Slf4j
@Component
public class MathTool implements ToolExecutor {

    private static final String TOOL_NAME = "math";

    @Override
    public String execute(String arguments) {
        try {
            log.info("Executing math function with arguments: {}", arguments);

            // 解析数学函数
            MathExpression expression = parseMathExpression(arguments);
            if (expression == null) {
                return "无法解析数学表达式";
            }

            double result = evaluateMathExpression(expression);
            return String.format("数学计算结果：%s(%s) = %f",
                expression.getFunction(),
                expression.getValue(),
                result
            );
        } catch (Exception e) {
            log.error("Error executing math tool", e);
            return "数学计算失败：" + e.getMessage();
        }
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    /**
     * 解析数学表达式
     */
    private MathExpression parseMathExpression(String arguments) {
        // 尝试提取JSON中的函数和值
        Pattern jsonPattern = Pattern.compile(
            "\"function\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"value\"\\s*:\\s*(\\d+(?:\\.\\d+)?)"
        );
        Matcher jsonMatcher = jsonPattern.matcher(arguments);
        if (jsonMatcher.find()) {
            String function = jsonMatcher.group(1).toLowerCase();
            double value = Double.parseDouble(jsonMatcher.group(2));
            return new MathExpression(function, value);
        }

        // 尝试提取文本中的函数和值
        Pattern textPattern = Pattern.compile("([a-zA-Z]+)\\(([\\d.]+)\\)");
        Matcher textMatcher = textPattern.matcher(arguments);
        if (textMatcher.find()) {
            String function = textMatcher.group(1).toLowerCase();
            double value = Double.parseDouble(textMatcher.group(2));
            return new MathExpression(function, value);
        }

        return null;
    }

    /**
     * 计算数学表达式
     */
    private double evaluateMathExpression(MathExpression expression) {
        switch (expression.getFunction()) {
            case "sin":
                return Math.sin(expression.getValue());
            case "cos":
                return Math.cos(expression.getValue());
            case "tan":
                return Math.tan(expression.getValue());
            case "asin":
            case "arcsin":
                return Math.asin(expression.getValue());
            case "acos":
            case "arccos":
                return Math.acos(expression.getValue());
            case "atan":
            case "arctan":
                return Math.atan(expression.getValue());
            case "log":
                return Math.log(expression.getValue());
            case "log10":
                return Math.log10(expression.getValue());
            case "exp":
                return Math.exp(expression.getValue());
            case "sqrt":
                return Math.sqrt(expression.getValue());
            case "abs":
                return Math.abs(expression.getValue());
            case "floor":
                return Math.floor(expression.getValue());
            case "ceil":
            case "ceiling":
                return Math.ceil(expression.getValue());
            case "round":
                return Math.round(expression.getValue());
            default:
                throw new IllegalArgumentException("不支持的数学函数：" + expression.getFunction());
        }
    }

    /**
     * 数学表达式内部类
     */
    private static class MathExpression {
        private final String function;
        private final double value;

        public MathExpression(String function, double value) {
            this.function = function;
            this.value = value;
        }

        public String getFunction() {
            return function;
        }

        public double getValue() {
            return value;
        }
    }
}