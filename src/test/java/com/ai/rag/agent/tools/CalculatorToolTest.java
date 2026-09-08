package com.ai.rag.agent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 计算器工具测试类
 */
class CalculatorToolTest {

    private CalculatorTool calculatorTool;

    @BeforeEach
    void setUp() {
        calculatorTool = new CalculatorTool();
    }

    @Test
    void testExecuteSimpleAddition() {
        String result = calculatorTool.execute("{\"expression\": \"1 + 2\"}");
        assertNotNull(result);
        assertTrue(result.contains("3"));
    }

    @Test
    void testExecuteSimpleSubtraction() {
        String result = calculatorTool.execute("{\"expression\": \"10 - 3\"}");
        assertNotNull(result);
        assertTrue(result.contains("7"));
    }

    @Test
    void testExecuteSimpleMultiplication() {
        String result = calculatorTool.execute("{\"expression\": \"5 * 6\"}");
        assertNotNull(result);
        assertTrue(result.contains("30"));
    }

    @Test
    void testExecuteSimpleDivision() {
        String result = calculatorTool.execute("{\"expression\": \"10 / 2\"}");
        assertNotNull(result);
        assertTrue(result.contains("5"));
    }

    @Test
    void testExecuteWithParentheses() {
        String result = calculatorTool.execute("{\"expression\": \"(1 + 2) * 3\"}");
        assertNotNull(result);
        assertTrue(result.contains("9"));
    }

    @Test
    void testExecuteWithInvalidExpression() {
        String result = calculatorTool.execute("{\"expression\": \"abc\"}");
        assertNotNull(result);
        assertTrue(result.contains("错误") || result.contains("无效"));
    }

    @Test
    void testGetName() {
        assertEquals("calculator", calculatorTool.getName());
    }
}