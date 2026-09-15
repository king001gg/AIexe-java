package com.ai.rag.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API Key 校验测试
 */
class ApiKeyPropertiesTest {

    private static ApiKeyProperties.ApiKey apiKey(String name, String key) {
        ApiKeyProperties.ApiKey apiKey = new ApiKeyProperties.ApiKey();
        apiKey.setName(name);
        apiKey.setKey(key);
        return apiKey;
    }

    private static ApiKeyProperties properties(ApiKeyProperties.ApiKey... keys) {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setApiKeys(List.of(keys));
        return properties;
    }

    @Test
    @DisplayName("命中密钥返回对应调用方名称")
    void resolvesCallerName() {
        ApiKeyProperties properties = properties(
                apiKey("web", "key-web"),
                apiKey("batch", "key-batch"));

        assertThat(properties.resolveName("key-web")).isEqualTo("web");
        assertThat(properties.resolveName("key-batch")).isEqualTo("batch");
    }

    @Test
    @DisplayName("未命中 / 缺失密钥返回 null")
    void rejectsUnknownKeys() {
        ApiKeyProperties properties = properties(apiKey("web", "key-web"));

        assertThat(properties.resolveName("wrong")).isNull();
        assertThat(properties.resolveName(null)).isNull();
        assertThat(properties.resolveName("")).isNull();
        assertThat(properties.resolveName("   ")).isNull();
    }

    @Test
    @DisplayName("未配置任何密钥时一律不通过（避免空配置意外放行）")
    void rejectsWhenNoKeysConfigured() {
        ApiKeyProperties properties = new ApiKeyProperties();

        assertThat(properties.resolveName("anything")).isNull();
    }

    @Test
    @DisplayName("跳过 key 为空的条目，且 name 缺失时回退为 unnamed")
    void handlesIncompleteEntries() {
        ApiKeyProperties withBlankKey = properties(apiKey("web", ""), apiKey("name-less", "k2"));
        assertThat(withBlankKey.resolveName("k2")).isEqualTo("name-less");
        assertThat(withBlankKey.resolveName("")).isNull();

        ApiKeyProperties nullName = properties(apiKey(null, "k3"));
        assertThat(nullName.resolveName("k3")).isEqualTo("unnamed");
    }

    @Test
    @DisplayName("默认不启用认证且请求头为 X-API-Key")
    void hasSafeDefaults() {
        ApiKeyProperties properties = new ApiKeyProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getHeader()).isEqualTo("X-API-Key");
    }
}
