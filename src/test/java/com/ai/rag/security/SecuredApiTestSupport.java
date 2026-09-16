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
 * <p>属性覆盖必须复述父类里**仍然需要**的那条（{@code spring.jpa.open-in-view=false}）：
 * 注解不合并，子类声明的 {@code properties} 会整体替换父类的，漏写就会退回 Spring Boot
 * 默认值 {@code true}——也就是退回**缺陷 D8 的配置**（懒加载在测试里恰好能工作，
 * 把「返回实体 + 序列化懒关联」的必现故障掩盖成永远绿）。
 * 此前这里复述的是 {@code rag.retrieval.min-score}，那是 D3 的临时 workaround，已随 D3 修复删除。
 *
 * <p>限流在此上下文关闭：429 会打断其余安全断言，限流本身有独立的
 * {@code RateLimitIntegrationTest} 专门验证。
 */
@SpringBootTest(properties = {
        "spring.jpa.open-in-view=false",
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
