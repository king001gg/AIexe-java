package com.ai.rag.agent.tools;

import com.ai.rag.model.entity.Document;
import com.ai.rag.service.RagService;
import com.ai.rag.service.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库检索工具
 * 检索私有知识库中的相关内容
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchTool implements ToolExecutor {

    private static final String TOOL_NAME = "search";
    private static final int DEFAULT_TOP_K = 5;

    private final RagService ragService;

    @Override
    public String execute(String arguments) {
        try {
            log.info("Executing knowledge base search with arguments: {}", arguments);

            // 提取查询内容
            String query = extractQuery(arguments);
            if (query == null) {
                return "无法解析查询内容";
            }

            // 执行检索
            List<Document> results = ragService.searchRelevantDocuments(query, null, DEFAULT_TOP_K);

            // 构建检索上下文
            return ragService.buildSearchContext(results);
        } catch (Exception e) {
            log.error("Error executing search tool", e);
            return "检索失败：" + e.getMessage();
        }
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    /**
     * 提取查询内容
     */
    private String extractQuery(String arguments) {
        // 尝试提取JSON中的查询
        Pattern jsonPattern = Pattern.compile("\"query\"\\s*:\\s*\"([^\"]+)\"");
        Matcher jsonMatcher = jsonPattern.matcher(arguments);
        if (jsonMatcher.find()) {
            return jsonMatcher.group(1);
        }

        // 尝试提取纯文本查询
        Pattern textPattern = Pattern.compile("(?:查询|搜索|query|search)[:：]\\s*([^,\\n]+)");
        Matcher textMatcher = textPattern.matcher(arguments);
        if (textMatcher.find()) {
            return textMatcher.group(1).trim();
        }

        // 如果没有特定格式，返回整个参数
        return arguments.trim();
    }
}