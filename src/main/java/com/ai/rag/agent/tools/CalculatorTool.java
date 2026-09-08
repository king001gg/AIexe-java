package com.ai.rag.agent.tools;

import com.ai.rag.service.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * 计算器工具
 * 支持四则运算和基本数学函数
 */
@Slf4j
@Component
public class CalculatorTool implements ToolExecutor {

    private static final String TOOL_NAME = "calculator";

    @Override
    public String execute(String arguments) {
        try {
            log.info("Executing calculator with arguments: {}", arguments);

            // 提取表达式
            String expression = extractExpression(arguments);
            if (expression == null) {
                return "无法解析计算表达式";
            }

            double result = evaluateExpression(expression);
            return String.format("计算结果：%s = %f", expression, result);
        } catch (Exception e) {
            log.error("Error executing calculator", e);
            return "计算错误：" + e.getMessage();
        }
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    /**
     * 提取表达式
     */
    private String extractExpression(String arguments) {
        // 尝试提取JSON中的表达式
        Pattern jsonPattern = Pattern.compile("\"expression\"\\s*:\\s*\"([^\"]+)\"");
        Matcher jsonMatcher = jsonPattern.matcher(arguments);
        if (jsonMatcher.find()) {
            return jsonMatcher.group(1);
        }

        // 尝试提取纯文本表达式
        Pattern textPattern = Pattern.compile("([0-9+\\-*/().^%\\s]+)");
        Matcher textMatcher = textPattern.matcher(arguments);
        if (textMatcher.find()) {
            return textMatcher.group(1).trim();
        }

        return null;
    }

    /**
     * 计算表达式
     */
    private double evaluateExpression(String expression) {
        // 去除空白字符
        expression = expression.replaceAll("\\s+", "");

        // 简单的表达式求值器（支持+、-、*、/、%、^）
        return evaluateSimple(expression);
    }

    /**
     * 简单表达式求值（递归下降，正确处理运算符优先级）
     */
    private double evaluateSimple(String expression) {
        return new ExpressionParser(expression).parse();
    }

    /**
     * 递归下降表达式解析器
     * 优先级：括号 > 一元正负 > 幂(^，右结合) > 乘除模(* / %) > 加减(+ -)
     */
    private static class ExpressionParser {
        private final String expr;
        private int pos = 0;

        ExpressionParser(String expr) {
            this.expr = expr;
        }

        double parse() {
            double result = parseAddSub();
            if (pos != expr.length()) {
                throw new IllegalArgumentException("无法解析的表达式片段：" + expr.substring(pos));
            }
            return result;
        }

        /** 加减（最低优先级，左结合） */
        private double parseAddSub() {
            double left = parseMulDiv();
            while (pos < expr.length()) {
                char op = expr.charAt(pos);
                if (op != '+' && op != '-') {
                    break;
                }
                pos++;
                double right = parseMulDiv();
                left = (op == '+') ? left + right : left - right;
            }
            return left;
        }

        /** 乘除模（左结合） */
        private double parseMulDiv() {
            double left = parsePower();
            while (pos < expr.length()) {
                char op = expr.charAt(pos);
                if (op != '*' && op != '/' && op != '%') {
                    break;
                }
                pos++;
                double right = parsePower();
                if (op == '*') {
                    left = left * right;
                } else if (op == '/') {
                    left = left / right;
                } else {
                    left = left % right;
                }
            }
            return left;
        }

        /** 幂（右结合） */
        private double parsePower() {
            double base = parseUnary();
            if (pos < expr.length() && expr.charAt(pos) == '^') {
                pos++;
                double exponent = parsePower();
                base = Math.pow(base, exponent);
            }
            return base;
        }

        /** 一元正负号 */
        private double parseUnary() {
            if (pos < expr.length() && (expr.charAt(pos) == '+' || expr.charAt(pos) == '-')) {
                char op = expr.charAt(pos++);
                double value = parseUnary();
                return (op == '-') ? -value : value;
            }
            return parsePrimary();
        }

        /** 数字或括号 */
        private double parsePrimary() {
            if (pos >= expr.length()) {
                throw new IllegalArgumentException("表达式不完整");
            }
            if (expr.charAt(pos) == '(') {
                pos++;
                double value = parseAddSub();
                if (pos >= expr.length() || expr.charAt(pos) != ')') {
                    throw new IllegalArgumentException("括号不匹配");
                }
                pos++;
                return value;
            }
            int start = pos;
            while (pos < expr.length() && (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("无效字符：" + expr.charAt(pos));
            }
            return Double.parseDouble(expr.substring(start, pos));
        }
    }
}