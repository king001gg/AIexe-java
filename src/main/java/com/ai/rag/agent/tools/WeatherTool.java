package com.ai.rag.agent.tools;

import com.ai.rag.service.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 天气查询工具
 * 集成天气API查询天气信息
 */
@Slf4j
@Component
public class WeatherTool implements ToolExecutor {

    private static final String TOOL_NAME = "weather";
    private static final String WEATHER_API_BASE_URL = "https://api.openweathermap.org/data/2.5/weather";
    private static final String DEFAULT_API_KEY = "your-weather-api-key";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public String execute(String arguments) {
        try {
            log.info("Executing weather query with arguments: {}", arguments);

            // 提取城市名称
            String city = extractCity(arguments);
            if (city == null) {
                return "无法解析城市名称，请提供要查询天气的城市";
            }

            return queryWeather(city);
        } catch (Exception e) {
            log.error("Error executing weather tool", e);
            return "天气查询失败：" + e.getMessage();
        }
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    /**
     * 提取城市名称
     */
    private String extractCity(String arguments) {
        // 尝试提取JSON中的城市
        Pattern jsonPattern = Pattern.compile("\"city\"\\s*:\\s*\"([^\"]+)\"");
        Matcher jsonMatcher = jsonPattern.matcher(arguments);
        if (jsonMatcher.find()) {
            return jsonMatcher.group(1);
        }

        // 尝试提取纯文本中的城市名称
        Pattern textPattern = Pattern.compile("城市[:：]\\s*([^,\\s，。]+)");
        Matcher textMatcher = textPattern.matcher(arguments);
        if (textMatcher.find()) {
            return textMatcher.group(1);
        }

        // 尝试提取中文城市名称
        Pattern chinesePattern = Pattern.compile("([\\u4e00-\\u9fa5]{2,})");
        Matcher chineseMatcher = chinesePattern.matcher(arguments);
        if (chineseMatcher.find()) {
            return chineseMatcher.group(1);
        }

        return null;
    }

    /**
     * 查询天气
     */
    private String queryWeather(String city) {
        try {
            // 构建天气查询URL
            String url = String.format("%s?q=%s&appid=%s&units=metric&lang=zh_cn",
                WEATHER_API_BASE_URL,
                city,
                DEFAULT_API_KEY
            );

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return formatWeatherResponse(city, response.body());
            } else {
                return "天气查询失败，HTTP状态码：" + response.statusCode();
            }
        } catch (Exception e) {
            log.error("Error querying weather", e);
            return "天气查询失败：" + e.getMessage();
        }
    }

    /**
     * 格式化天气响应
     */
    private String formatWeatherResponse(String city, String jsonResponse) {
        try {
            // 这里应该解析天气API的JSON响应
            // 由于需要引入JSON解析库，这里简化为返回城市名称和温度信息
            StringBuilder result = new StringBuilder();
            result.append("天气查询结果：\n");
            result.append("城市：").append(city).append("\n");
            result.append("天气数据已获取，请查看详细JSON：\n");
            result.append(jsonResponse);
            return result.toString();
        } catch (Exception e) {
            log.error("Error formatting weather response", e);
            return "天气数据解析失败：" + e.getMessage();
        }
    }
}