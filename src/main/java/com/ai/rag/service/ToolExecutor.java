package com.ai.rag.service;

/**
 * 工具执行器接口
 */
public interface ToolExecutor {
    /**
     * 执行工具
     */
    String execute(String arguments);

    /**
     * 获取工具名称
     */
    String getName();
}