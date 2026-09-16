package com.ai.rag.security;

import com.ai.rag.support.IntegrationTestSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 「认证开启」的集成测试基类
 *
 * <p>{@code test} profile 默认把 {@code security.enabled} 关掉（方便业务测试直接打接口），
 * 所以安全相关的链路必须在**另一份上下文**里跑：重新声明 {@code @SpringBootTest(properties=...)}
 * 会得到一份不同的 {@code MergedContextConfiguration}，Spring 会另建并缓存一个上下文。
 *
 * <p>属性覆盖包含父类的 {@code rag.retrieval.min-score}：注解不合并，
 * 子类声明的 {@code properties} 会整体替换父类的，漏写就会退回生产阈值。
 *
 * <p>限流在此上下文关闭：429 会打断其余安全断言，限流本身有独立的
 * {@code RateLimitIntegrationTest} 专门验证。
 */
@SpringBootTest(properties = {
        "rag.retrieval.min-score=0.55",
        "security.enabled=true",
        "security.header=X-API-Key",
        "security.api-keys[0].name=test-client",
        "security.api-keys[0].key=test-key",
        "rate-limit.enabled=false"
})
@ActiveProfiles("test")
public abstract class SecuredApiTestSupport extends IntegrationTestSupport {

    protected static final String API_KEY_HEADER = "X-API-Key";
    protected static final String VALID_API_KEY = "test-key";
}
