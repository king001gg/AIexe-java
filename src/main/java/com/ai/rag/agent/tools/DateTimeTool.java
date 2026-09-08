package com.ai.rag.agent.tools;

import com.ai.rag.service.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日期时间工具
 * 查询当前日期、时间、星期等信息
 */
@Slf4j
@Component
public class DateTimeTool implements ToolExecutor {

    private static final String TOOL_NAME = "datetime";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String execute(String arguments) {
        try {
            log.info("Executing date time query with arguments: {}", arguments);

            String queryType = extractQueryType(arguments);

            return generateDateTimeResponse(queryType);
        } catch (Exception e) {
            log.error("Error executing date time tool", e);
            return "日期时间查询失败：" + e.getMessage();
        }
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    /**
     * 提取查询类型
     */
    private String extractQueryType(String arguments) {
        String lowerArgs = arguments.toLowerCase();

        if (lowerArgs.contains("date") || lowerArgs.contains("日期")) {
            return "date";
        } else if (lowerArgs.contains("time") || lowerArgs.contains("时间")) {
            return "time";
        } else if (lowerArgs.contains("week") || lowerArgs.contains("星期")) {
            return "week";
        } else if (lowerArgs.contains("year") || lowerArgs.contains("年")) {
            return "year";
        } else if (lowerArgs.contains("month") || lowerArgs.contains("月")) {
            return "month";
        } else {
            return "datetime";
        }
    }

    /**
     * 生成日期时间响应
     */
    private String generateDateTimeResponse(String queryType) {
        LocalDateTime now = LocalDateTime.now();
        ZoneId zoneId = ZoneId.systemDefault();

        StringBuilder response = new StringBuilder();
        response.append("当前日期时间信息：\n");

        switch (queryType) {
            case "date":
                response.append("日期：").append(now.format(DATE_FORMATTER)).append("\n");
                break;
            case "time":
                response.append("时间：").append(now.format(TIME_FORMATTER)).append("\n");
                break;
            case "week":
                DayOfWeek dayOfWeek = now.getDayOfWeek();
                response.append("星期：").append(getChineseWeek(dayOfWeek)).append("\n");
                response.append("日期：").append(now.format(DATE_FORMATTER)).append("\n");
                break;
            case "year":
                response.append("年份：").append(now.getYear()).append("年\n");
                response.append("当前为第").append(now.getDayOfYear()).append("天\n");
                break;
            case "month":
                response.append("月份：").append(now.getMonthValue()).append("月\n");
                response.append("本月第").append(now.getDayOfMonth()).append("天\n");
                break;
            case "datetime":
            default:
                response.append("日期时间：").append(now.format(DATETIME_FORMATTER)).append("\n");
                response.append("星期：").append(getChineseWeek(now.getDayOfWeek())).append("\n");
                response.append("时区：").append(zoneId.getId()).append("\n");
                break;
        }

        return response.toString();
    }

    /**
     * 获取中文星期
     */
    private String getChineseWeek(DayOfWeek dayOfWeek) {
        switch (dayOfWeek) {
            case MONDAY: return "星期一";
            case TUESDAY: return "星期二";
            case WEDNESDAY: return "星期三";
            case THURSDAY: return "星期四";
            case FRIDAY: return "星期五";
            case SATURDAY: return "星期六";
            case SUNDAY: return "星期日";
            default: return "未知";
        }
    }
}