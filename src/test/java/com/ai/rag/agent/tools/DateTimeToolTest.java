package com.ai.rag.agent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 日期时间工具测试类
 */
class DateTimeToolTest {

    private DateTimeTool dateTimeTool;

    @BeforeEach
    void setUp() {
        dateTimeTool = new DateTimeTool();
    }

    @Test
    void testExecuteDateQuery() {
        String result = dateTimeTool.execute("查询当前日期");
        assertNotNull(result);
        assertTrue(result.contains("日期"));
    }

    @Test
    void testExecuteTimeQuery() {
        String result = dateTimeTool.execute("查询当前时间");
        assertNotNull(result);
        assertTrue(result.contains("时间"));
    }

    @Test
    void testExecuteWeekQuery() {
        String result = dateTimeTool.execute("查询星期");
        assertNotNull(result);
        assertTrue(result.contains("星期"));
    }

    @Test
    void testGetName() {
        assertEquals("datetime", dateTimeTool.getName());
    }
}