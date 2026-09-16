# 企业级测试报告 —— AI RAG 问答 Agent

| 项目 | 值 |
| --- | --- |
| 被测版本 | `ec98d0c`（分支 `feat/langchain4j-migration-hardening`）+ 未提交的测试代码 |
| 技术栈 | Spring Boot 3.4 / Java 17 / LangChain4j 0.36.2 / Milvus / MySQL / Redis |
| 测试框架 | JUnit 5 + AssertJ + MockMvc + JSONPath + JaCoCo 0.8.12 |
| 执行日期 | 2026-09-15（测试轮次）、2026-09-16（三轮修复） |
| 结论 | **162 个用例全绿；共发现 11 个缺陷（7 高 / 2 中 / 2 低）；其中 D1 / D2 / D6 / D7 / D8 / D10 / D11 已修复并验证，其余 4 个仅报告** |

> **修复轮次一（2026-09-16）**：修「HTTP 契约」层
> —— **D10**（浏览器访问全接口被协商成 XML）、**D1**（框架异常被降级成 500）、
> **D2**（404 泄漏内部措辞）。用例数 151 → 155。见第九节。
>
> **修复轮次二（2026-09-16）**：修「开箱可用」层
> —— **D8**（4 个读端点因懒加载必 500）、**D7**（无 Redis 时 health 502/503 不 Ready），
> 并给根路径加了端点清单入口。用例数 155 → 160，行覆盖 60.08% → **62.58%**。见第十节。
>
> **修复轮次二追加（同日）**：接入真实 DeepSeek 端点做端到端验证时，发现了
> **D11**（用过工具的会话查上下文必 500）——这是一个**只有真实模型才会触发**的缺陷，
> 桩模型和 MockMvc 都覆盖不到。已修复。用例数 160 → 162，行覆盖 62.58% → **62.84%**。
>
> **修复轮次三（2026-09-16）**：修 **D6**（同一知识库第二次上传必定失败
> —— `chunk_id` 只由「知识库 ID + 本次上传内的分块序号」构成，必然撞唯一键）。
> 用例数不变（162），其中两条缺陷快照测试翻转为正向契约断言，并新增一条可追溯性断言。
> 见第十一节。
>
> 剩余 **D3 / D4 / D5 / D9 未修**，仍为仅报告。

---

## 一、执行摘要

### 1.1 结果总览

```
Tests run: 162, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

测试规模从 70 个增长到 162 个（+92），覆盖了此前**完全没有测试**的四条主链路：
对话落库、工具调用、多路召回注入、SSE 流式输出。
（测试轮次结束时为 151 个；三轮修复中新增 11 个回归用例。）

主代码最终覆盖率：**行 62.69% / 分支 57.09% / 指令 63.03% / 方法 71.83%**。

### 1.2 覆盖率前后对比

| 指标 | 测试前 | 测试后 | 变化 |
| --- | --- | --- | --- |
| 指令覆盖 | 35.42% | **60.44%** | +25.0pp |
| 分支覆盖 | 37.17% | **56.52%** | +19.4pp |
| 行覆盖 | 35.39% | **60.08%** | +24.7pp |
| 方法覆盖 | 44.19% | **68.99%** | +24.8pp |

> 「测试前」= 在 `ec98d0c` 原始主代码上仅运行原有 70 个用例；
> 「测试后」= 运行全部 155 个用例。
>
> ⚠️ 修复轮次后这两个数字**不再是严格同源的对比**：修复 D1/D2/D10 改动了主代码
> （`GlobalExceptionHandler` 新增约 60 行处理器、`pom.xml` 排除一个依赖），
> 分母变大了。新增的处理器全部被新用例覆盖，因此覆盖率不降反微升
> （行 59.89% → 60.08%）。若要严格复现「测试前」基线，需 checkout `ec98d0c` 的
> `GlobalExceptionHandler` 再跑。

按包看变化最剧烈的地方：

| 包 | 测试前 | 测试后 | 说明 |
| --- | --- | --- | --- |
| `com.ai.rag.service` | 15.16% | **60.81%** | 对话/Tool/RAG/SSE 业务链路 |
| `com.ai.rag.controller` | 2.08% | **30.83%** | API 契约 + 上下文端点 |
| `com.ai.rag.exception` | 14.29% | **100.00%** | 全部异常分支 |
| `com.ai.rag.security` | 72.60% | **97.26%** | 过滤器链装配 |
| `com.ai.rag.config` | 82.48% | **93.43%** | 装配与降级 |
| `com.ai.rag.service.retrieval` | 56.86% | **86.27%** | RRF 融合 |
| `com.ai.rag.model.dto` | 5.88% | **41.18%** | 响应体结构 |

### 1.3 缺陷清单

| 编号 | 严重度 | 标题 | 状态 |
| --- | --- | --- | --- |
| **D1** | 🔴 高 | 405 / 415 / 404 被全局兜底误报为 **500** | ✅ **已修复**（第九节） |
| **D6** | 🔴 高 | 同一知识库第二次上传文档**必定失败** | ✅ **已修复**（第十一节） |
| **D8** | 🔴 高 | 4 个返回 JPA 实体的读端点全部 **500**（LazyInitialization） | ✅ **已修复**（第十节） |
| **D7** | 🔴 高 | Redis 不可用时 `/actuator/health` 报 **503 DOWN**，服务无法就绪 | ✅ **已修复**（第十节） |
| **D3** | 🔴 高 | 降级向量库的 score 语义与 Milvus 不一致，`min-score` 形同虚设 | 仅报告 |
| **D10** | 🔴 高 | 浏览器访问时**全部接口**（含成功响应）被内容协商成 XML 而非 JSON | ✅ **已修复**（第九节） |
| **D5** | 🟠 中 | 并发 token 记账撞唯一约束 `uk_session_date`，统计丢失 | 仅报告 |
| **D4** | 🟠 中 | 并发首次访问同一 session 产生重复会话，会话被**永久打坏** | 仅报告 |
| **D9** | 🟡 低 | 工具执行端点字段缺失时静默降级为 `success: true` | 仅报告 |
| **D2** | 🟡 低 | 404 响应体泄漏 Spring 内部措辞 | ✅ **已修复**（第九节） |
| **D11** | 🔴 高 | 用过工具的会话查上下文必 **500**（`Map.of` 不接受 null） | ✅ **已修复**（第十节） |

> D1~D6 来自自动化测试，D7~D10 来自 `local` profile 下的真实启动冒烟测试（见第七节），
> D11 来自接入真实模型后的端到端验证（见第十节）。
> 合计 **11 个缺陷：7 高 / 2 中 / 2 低**，其中 **7 个已修复**
> （D1 / D2 / D6 / D7 / D8 / D10 / D11），4 个仅报告。

**故障固化机制**：D1/D2/D3/D4/D5/D6 在测试轮次以「缺陷快照测试」（characterization test）固化，
断言写的是**当前真实行为**。这正是本轮修复的抓手——D1/D2 修好后，
`ErrorHandlingContractTest` 立刻从「断言 500」翻转为「断言 405/415/404」，
失败信号精确指向需要更新的断言，缺陷不会悄悄漂移。
D10 修复时新增了 `ContentNegotiationTest` 作为回归防护。
（D7/D8/D9 仍无用例，修复时应一并补上。）

---

## 二、测试策略

### 2.1 遇到的核心障碍与解法

这个项目最大的测试障碍是：**AI 层依赖外部服务**。没有 `OPENAI_API_KEY`、没有 Milvus，
导致对话、工具调用、向量检索、token 计量全部落在测试盲区——上一轮测得 `service` 包
只有 15% 行覆盖，正是这个原因。

解法是自建三个确定性桩（`src/test/java/com/ai/rag/support/`）：

| 桩 | 替代 | 关键设计 |
| --- | --- | --- |
| `StubChatLanguageModel` | OpenAI ChatLanguageModel | 脚本化应答队列。可压入纯文本应答、**工具调用应答**、异常。记录每次调用的完整消息列表与工具声明 |
| `StubStreamingChatLanguageModel` | 流式模型 | 同步逐 token 推送给 `StreamingResponseHandler`，可控地在中途抛异常 |
| `DeterministicEmbeddingModel` | 真实 embedding | 1536 维词袋哈希向量。**中文按相邻双字 2-gram** 切分——刻意不取单字，否则任意两段中文余弦都 >0，「无关问题不召回」这类断言就永远为真 |

三个桩让整条 AI 链路在**无网络、无外部服务、完全确定性**的条件下可端到端断言。

### 2.2 测试上下文设计

所有集成测试共用一个 Spring 上下文（`IntegrationTestSupport`），否则每个类都要重跑
一次完整启动（含 Flyway 迁移），测试会慢到没人愿意跑。两个关键处理：

1. **用 `BeanFactoryPostProcessor` 换掉 Milvus**，而不是 `@Primary`。
   本机没有 Milvus，`MilvusEmbeddingStore` 会先阻塞约 10 秒的 `DEADLINE_EXCEEDED` 才降级，
   每个上下文白付一次。注意：`@Primary` **做不到**这件事——Spring 会实例化所有非懒加载单例，
   原 bean 照样会被构造。必须在 bean 定义阶段就替换。
   替换后仍是功能等价的真实 `InMemoryEmbeddingStore`，不是 mock。

2. **`rag.retrieval.min-score=0.55`**。这个值必须结合缺陷 D3 理解：
   降级库返回的 score 是 `(cosine+1)/2`，完全无关的文本 score 也是 0.5。
   用生产默认值 `0.3` 等价于「余弦 ≥ -0.4」，等于关掉阈值。
   取 0.55 恰好等价于「原始余弦 ≥ 0.1」，既挡住零重叠又放行真实命中。

### 2.3 覆盖的测试维度

| 维度 | 测试类 | 用例数 |
| --- | --- | --- |
| 基础设施自检 | `InfrastructureSmokeTest` | 5 |
| API 契约 | `ChatApiContractTest` | 12 |
| 上下文端点 | `ChatContextEndpointTest` | 6 |
| 异常映射契约 | `ErrorHandlingContractTest` | 8 |
| 认证集成 | `ApiKeySecurityIntegrationTest` | 10 |
| 限流集成 | `RateLimitIntegrationTest` | 6 |
| SSE 流式契约 | `StreamingChatSseIntegrationTest` | 7 |
| 业务链路（记忆/Tool/RAG） | `ChatFlowIntegrationTest` | 9 |
| 边界/降级/并发 | `BoundaryAndConcurrencyTest` | 17 |
| **新增小计** | | **80** |
| 原有单测（工具、解析、RRF、限流算法等） | | 70 |
| **合计** | | **151** |

---

## 三、缺陷详情

### D1 🔴 框架级异常被降级为 500 ｜ ✅ 已于 2026-09-16 修复（第九节）

**严重度：高** ｜ **证据：`ErrorHandlingContractTest`（5 条）** ｜ **位置：`GlobalExceptionHandler:47`**

#### 现象

| 请求 | 应有响应 | 实际响应 |
| --- | --- | --- |
| `GET /api/chat/message`（该端点只支持 POST） | 405 | **500** |
| `POST /api/chat/message` 带 `Content-Type: text/plain` | 415 | **500** |
| `POST /api/chat/message` 不声明 Content-Type | 415 | **500** |
| `GET /api/definitely-not-exists` | 404 | **500** |
| `GET /api/chat/context/`（缺路径变量） | 404 | **500** |

#### 根因

```java
@ExceptionHandler(Exception.class)          // ← 兜底范围过大
public ResponseEntity<ApiError> handleGeneric(Exception e, ...) {
    return build(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误", e.getMessage(), request);
}
```

`@ExceptionHandler(Exception.class)` 把 Spring MVC 的框架级异常也一并接住了：
`HttpRequestMethodNotSupportedException`、`HttpMediaTypeNotSupportedException`、
`NoResourceFoundException`。这些异常本该由 `DefaultHandlerExceptionResolver` 映射为
405 / 415 / 404，现在全被吃掉变成 500。

#### 影响

1. **监控误告警**：客户端写错 URL 或方法就会在 APM 里打出一条 ERROR 级 500，
   真实故障被淹没在噪音里，告警疲劳。
2. **客户端无法区分责任方**：拿不到 4xx 就无法判断「是我请求错了」还是「服务器挂了」，
   也就无法决定重试与否。
3. **网关按 5xx 重试**：多数 API 网关对 5xx 自动重试、对 4xx 不重试。
   于是 `POST /chat/message` 带错 Content-Type 这种请求会被反复重试，
   在上传/写库类端点上可能造成重复写入与重复计费。

#### 建议修复

```java
@ExceptionHandler(ErrorResponseException.class)   // 保留框架已判定的状态码
public ResponseEntity<ApiError> handleErrorResponse(ErrorResponseException e, HttpServletRequest request) {
    HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
    return build(status, status.getReasonPhrase(), e.getMessage(), request);
}
```

或让 `GlobalExceptionHandler` 继承 `ResponseEntityExceptionHandler`，
在 `handleExceptionInternal` 里统一包装成 `ApiError`——这样 4xx 语义由框架保证，
业务异常仍走兜底。**注意**：修完后 `ErrorHandlingContractTest` 里那 5 条用例会失败，
这是预期行为，需同步把断言改成 405 / 415 / 404。

---

### D6 🔴 同一知识库第二次上传必定失败 ✅ 已修复

**严重度：高** ｜ **证据：`BoundaryAndConcurrencyTest#secondUploadToSameKnowledgeBaseFails`、`#duplicateChunkIdRollsBackAtomicallyAndHidesTheCause`** ｜ **位置：`DocumentService:129/149`**

#### 现象

```
1) POST /api/documents/upload  file=a.txt  knowledgeBaseName=产品手册   → 200 OK
2) POST /api/documents/upload  file=b.txt  knowledgeBaseName=产品手册   → 500
   body: {"success":false,"message":"文档上传失败：Failed to process document"}
```

文档内容完全没有进库。**一个知识库名字只能用一次**，之后再也传不进任何新内容。

#### 根因

```java
document.setChunkId(generateChunkId(knowledgeBase.getId(), i));   // DocumentService:129

private String generateChunkId(Long knowledgeBaseId, int chunkIndex) {
    return String.format("kb_%d_chunk_%d", knowledgeBaseId, chunkIndex);   // DocumentService:149
}
```

`chunk_id` 只由「知识库 ID + **本次上传内的**分块序号」构成，与文件、时间、版本全都无关。
而表上有：

```sql
CONSTRAINT uk_knowledge_chunk UNIQUE (knowledge_base_id, chunk_id)
```

第二次上传时 `chunkIndex` 从 0 重新开始，插入 `kb_5_chunk_0` 立刻撞唯一键：

```
JdbcSQLIntegrityConstraintViolationException: Unique index or primary key violation:
"PUBLIC.UK_KNOWLEDGE_CHUNK_INDEX_D ON PUBLIC.DOCUMENTS(KNOWLEDGE_BASE_ID, CHUNK_ID) ...
VALUES ( /* key:5 */ CAST(5 AS BIGINT), 'kb_5_chunk_0')"
```

`processDocument` 上有 `@Transactional`，异常导致整个上传回滚——这是**唯一值得庆幸的一点**：
不会留下半个分块。但也意味着失败是彻底的，没有任何部分成功。

#### 影响

- 知识库**无法增量维护**。产品文档更新、追加资料、多文件入库全部不可用，
  只能删库重建（`DELETE /documents/knowledge-bases/{id}` 后重传全部内容）。
  对「多用户各自维护知识库」的定位来说是致命的功能性缺陷。
- 错误信息被 `DocumentService:77` 的 `new RuntimeException("Failed to process document", e)`
  吞掉了根因（`e.getMessage()` 只有那句固定文案），运维无法据此定位，
  必须翻服务端日志才能知道是唯一键冲突。

#### 建议修复

`chunk_id` 必须带上「文档身份」，例如引入文档级 UUID：

```java
String docId = UUID.randomUUID().toString();
document.setChunkId(docId + "_chunk_" + i);
```

或把唯一约束改成 `UNIQUE (knowledge_base_id, chunk_id, created_at)` 之类，
但更推荐前者——chunk_id 本就该全局唯一，这样也便于溯源。
同时把 `DocumentService:77` 的异常包装改为保留更具体的信息，
或在 `DocumentController` 里对 `DataIntegrityViolationException` 单独给出可读提示。

#### 修复（第十一节）

按推荐方案实施：`saveDocumentsWithVectors` 里为**每次上传**生成一个 UUID 作为文档身份，
`generateChunkId` 从 `kb_%d_chunk_%d`（知识库 ID + 本次分块序号）改为
`doc_%s_chunk_%d`（本次上传的文档 ID + 本次分块序号）。

`chunk_id` 由此**天然全局唯一**，`knowledge_base_id` 不再需要参与去重，
唯一约束无需改动（`VARCHAR(100)` 也装得下：`doc_` + 32 位十六进制 + `_chunk_` + 序号 ≈ 46 字符）。

> 关于「异常包装吞掉根因」：本轮**未改**。`DocumentController` 对上传失败返回
> `500 + "文档上传失败：" + e.getMessage()`，`e.getMessage()` 是 `DocumentService` 的固定文案
> `Failed to process document`，根因（唯一键冲突）只在服务端日志里。
> 之所以先不动：D6 修好后这条路径已不再是「必然触发」，而改文案涉及
> 「客户端该看到多少内部信息」的取舍——D2 已经确立了「不泄漏内部措辞」的原则，
> 真要改应统一设计成「可读的领域错误 + 409」，而不是在这里单独放宽。留作待办。

---

### D3 🔴 降级向量库 score 语义与 Milvus 不一致

**严重度：高（生产配置下）** ｜ **证据：`EmbeddingDiagTest`、`ChatFlowIntegrationTest#noHitMeansNoInjectionAndStillAnswers`** ｜ **位置：`RagService:142`、`MilvusConfig`**

#### 现象

`InMemoryEmbeddingStore` 返回的 similarity 是 **`(cosine + 1) / 2`**（LangChain4j 的 `RelevanceScore` 口径），
取值 `[0, 1]`；而 Milvus 的 `MetricType.COSINE` 返回**原始余弦**，取值 `[-1, 1]`。
两者共用同一个配置项：

```java
@Value("${rag.retrieval.min-score:0.3}")
private double minScore;      // ← 同一个阈值，两套语义
```

实测（`EmbeddingDiagTest`，快照）：

| 场景 | 原始余弦 | 降级库 score | `min-score=0.5` 是否召回 |
| --- | --- | --- | --- |
| 完全无关的文本 | 0.00 | **0.50** | ✅ 召回（错） |
| 完全无关的文本 | 0.00 | **0.50** | `min-score=0.6` 才挡住 |

#### 影响

生产默认 `min-score: 0.3` 的实际含义完全不同：

| 路径 | `min-score=0.3` 等价于 |
| --- | --- |
| Milvus（正常） | 余弦 ≥ 0.3 —— 合理 |
| InMemory 降级 | 余弦 ≥ **-0.4** —— **等于不设阈值** |

而「无 Milvus 时优雅降级」正是该分支最近一次提交的核心诉求，
即**降级路径在生产里是会被真实走到的**。后果是：降级期间每次提问都会把
N 条毫不相关的知识库分块塞进 prompt——既烧 token，又可能让模型基于噪声作答（幻觉）。

#### 建议修复

按 store 实现归一化阈值，或干脆在降级适配器里把 score 还原成余弦：

```java
double effectiveMinScore = (embeddingStore instanceof InMemoryEmbeddingStore)
        ? (minScore + 1) / 2      // 把「余弦阈值」换算成 RelevanceScore 口径
        : minScore;
```

更稳妥的做法是给 `EmbeddingStore` 包一层适配器统一到余弦口径，
避免这类「同一配置项在不同实现下语义漂移」的问题再次出现。

> 附带说明：本报告的测试上下文使用 `min-score=0.55`（等价余弦 ≥ 0.1），
> 就是为了让「无关问题不注入知识」这类断言在降级库下仍然成立。

---

### D5 🟠 并发 token 记账撞唯一约束

**严重度：中** ｜ **证据：`BoundaryAndConcurrencyTest#concurrentTokenUsageRecordingRaces`** ｜ **位置：`TokenService:31-56`**

#### 现象

12 个线程并发为**同一会话**记账，多线程抛
`DataIntegrityViolationException`（违反 `uk_session_date UNIQUE (session_id, date)`），
该会话当日 token 统计与实际用量不符。

#### 根因

典型的「先查后插 + 唯一约束」竞态：

```java
TokenUsage tokenUsage = tokenUsageRepository.findBySessionIdAndDate(sessionId, today);  // :34
if (tokenUsage == null) { tokenUsage = new TokenUsage(); ... }                          // :36-45
tokenUsage.setInputTokens(tokenUsage.getInputTokens() + inputTokens);                   // :48  读-改-写
tokenUsageRepository.save(tokenUsage);                                                  // :56
```

并发时多个线程同时查不到记录 → 同时走 insert 分支 → 只有一个成功。
即便不撞约束，`读-改-写` 本身也不是原子的，会丢更新（lost update）。

#### 影响

token 统计直接关联成本核算。丢失的记账意味着**账单与用量对不上**，
且用户在界面上看到的用量曲线会莫名偏低。触发条件只是「同一用户快速连发请求」。

#### 建议修复

数据库端原子 upsert（MySQL `INSERT ... ON DUPLICATE KEY UPDATE`，
H2 用 `MERGE INTO`），或改造为「累加 SQL」：

```java
@Modifying
@Query("UPDATE TokenUsage t SET t.inputTokens = t.inputTokens + :in, ... WHERE t.sessionId = :sid AND t.date = :d")
int accumulate(...);
```

配合「更新行数为 0 则 insert，撞唯一键则重试一次」的逻辑。

---

### D4 🟠 并发首次访问产生重复会话，且会话被永久打坏

**严重度：中** ｜ **证据：`BoundaryAndConcurrencyTest#concurrentConversationCreationRaces`、`#duplicateConversationsBreakTheSessionPermanently`** ｜ **位置：`ConversationService:30-39`**

#### 现象

12 个线程并发首次访问同一 `sessionId`，`conversations` 表出现多条同 `session_id` 记录。
之后该 session **所有请求永久 500**——包括 `/chat/message` 与记忆读写。

#### 根因

两层问题叠加。

第一层，创建不幂等且无唯一约束：

```java
return conversationRepository.findBySessionId(sessionId)      // :31  查到就返回
        .orElseGet(() -> { ... conversationRepository.save(conversation); });  // :37  查不到就插
```

`conversations.session_id` 只有普通索引 `idx_conversations_session_id`，**没有唯一约束**
（对比 `memories` 有 `uk_session_key`、`user_preferences` 有 `uk_user_preferences_session_id`，
显然是漏了），所以并发的第二次 insert 不会被数据库拦下。

第二层，一旦重复，整条链路永久失效：

```java
Optional<Conversation> findBySessionId(String sessionId);   // 返回 Optional
```

Spring Data 的 `Optional` 单值查询在**匹配到多行**时会抛
`IncorrectResultSizeDataAccessException`。于是 `ConversationService.saveMessage:46`、
`updateTitleIfDefault:62`、以及记忆读取全部炸掉，且**不会自愈**——
除非有人手工去数据库删掉重复行，用户会一直看到 500。

#### 影响

用户侧：快速连点发送、页面自动重试、多标签页同时打开同一个会话，任何一个动作都可能
把该会话永久打死。这是**用户可自触发、不可自恢复**的故障。

#### 建议修复

```sql
ALTER TABLE conversations ADD CONSTRAINT uk_conversations_session_id UNIQUE (session_id);
```

```java
@Transactional
public Conversation getOrCreateConversation(String sessionId, String nickname, String model) {
    return conversationRepository.findBySessionId(sessionId).orElseGet(() -> {
        try {
            ... return conversationRepository.save(conversation);
        } catch (DataIntegrityViolationException e) {
            return conversationRepository.findBySessionId(sessionId).orElseThrow();  // 别人抢先插了
        }
    });
}
```

另建议把 `findBySessionId` 在业务层改为「取第一条 + 告警」，作为兜底容错。

---

### D2 🟡 404 响应体泄漏内部实现措辞 ｜ ✅ 已于 2026-09-16 修复（第九节）

**严重度：低** ｜ **证据：`ErrorHandlingContractTest#notFoundDetailLeaksInternalWording`** ｜ **位置：`GlobalExceptionHandler:50`**

未知路径返回的 `detail` 字段是 `"No static resource definitely-not-exists."`，
暴露了「项目用静态资源兜底处理器接住了这个请求」这一内部实现细节。
单独看信息价值极低，但它属于「内部实现细节外泄」这一类问题，
在安全审计里会被记一笔——攻击者可据此推断框架版本与静态资源映射策略。

根因与 D1 相同（兜底接管了框架异常，并把 `e.getMessage()` 原样塞进 `detail`）。
修复 D1 后此问题一并消失。顺带建议 `handleUnreadable:41` 同样处理——
那里也是把 `e.getMessage()` 直接外传。

> **已在测试中验证的反面结论（避免误报）**：曾怀疑 `handleGeneric` 的
> `e.getMessage()` 会把 JDBC 连接串、密码等泄漏给客户端。实测**不成立**——
> `AgentService` 用 `RuntimeException("Failed to process request", e)` 包了一层固定文案，
> 外层拿到的 `getMessage()` 不含内部细节。
> `ChatApiContractTest#internalFailureDoesNotLeakInternals` 断言响应体不含
> `jdbc:mysql` / `s3cr3t` / `IllegalStateException`，验证通过。

---

### D8 🔴 4 个返回 JPA 实体的读端点全部 500 ｜ ✅ 已于 2026-09-16 修复（第十节）

**严重度：高** ｜ **证据：`local` profile 冒烟（见第七节）** ｜ **位置：`DocumentController:71/79/123/133`、`KnowledgeBase`/`Document` 实体**

#### 现象

在 `application-local.yml`（正确设置了 `spring.jpa.open-in-view: false`）下：

| 端点 | 结果 |
| --- | --- |
| `GET /documents/knowledge-bases` | **500** `failed to lazily initialize a collection of role: KnowledgeBase.documents` |
| `GET /documents/knowledge-bases/{id}` | **500** 同上 |
| `GET /documents/knowledge-bases/{id}/documents` | **500** `Could not initialize proxy [KnowledgeBase#1] - no session` |
| `GET /documents/search?query=...` | **500** `Could not initialize proxy [KnowledgeBase#1] - no session` |
| `GET /documents/search/detailed?query=...` | ✅ 200（**唯一正常的**，因为它映射成 `Map` 而不是直接返回实体） |

#### 根因

控制器直接把 JPA 实体当响应体返回：

```java
public ResponseEntity<List<KnowledgeBase>> getAllKnowledgeBases() {
    return ResponseEntity.ok(knowledgeBaseRepository.findAll());   // :72
}
```

`KnowledgeBase.documents` 是 `@OneToMany`（默认 LAZY）、`Document.knowledgeBase` 是 `@ManyToOne` 代理，
两者在事务外都被 Jackson 触发 → `LazyInitializationException`。

关键点在于它**只在部分配置下暴露**：`application.yml`（生产默认）没有设置 `open-in-view`，
Spring Boot 默认为 `true`，于是整个请求期间 EntityManager 保持打开，代码「碰巧能跑」。
而 `local` profile 显式关掉了它——这是**正确的**做法，却立刻让 4 个端点全挂。

#### 影响

- 任何遵循最佳实践关闭 `open-in-view` 的环境（多数团队在生产也会关）**这 4 个端点直接不可用**。
- 保持 `open-in-view: true` 则是在用「把数据库连接持有到视图渲染结束」换取「代码能跑」，
  高并发下连接池会被迅速耗尽，且掩盖 N+1 查询。
- 两种配置下总有一种是坏的——这正是它危险的地方：**CI 里跑通不代表部署后能跑**。

#### 建议修复

统一改为返回 DTO 或显式投影，不要序列化实体：

```java
@GetMapping("/knowledge-bases")
public ResponseEntity<List<KnowledgeBaseSummary>> getAllKnowledgeBases() {
    return ResponseEntity.ok(knowledgeBaseRepository.findAll().stream()
            .map(kb -> new KnowledgeBaseSummary(kb.getId(), kb.getName(), kb.getDocCount(), ...))
            .toList());
}
```

或在 repository 上用 `@EntityGraph` / `join fetch` 预取需要的关联，
并在实体上加 `@JsonIgnore` 断掉序列化路径。**推荐前者**——API 契约不应随实体结构漂移。
`/documents/search/detailed` 已经是正确写法的现成范本。

---

### D7 🔴 Redis 不可用时 health 报 DOWN，服务无法就绪 ｜ ✅ 已于 2026-09-16 修复（第十节）

**严重度：高（部署阻断）** ｜ **证据：`local` profile 冒烟（见第七节）** ｜ **位置：`pom.xml:39` + 缺失的健康检查配置**

#### 现象

```
GET /api/actuator/health  →  HTTP 503
{"status":"DOWN","components":{
  "db":{"status":"UP"}, "diskSpace":{"status":"UP"}, "ping":{"status":"UP"}, "ssl":{"status":"UP"},
  "redis":{"status":"DOWN","details":{"error":"RedisConnectionFailureException: Unable to connect to Redis"}}}}
```

只有 `redis` 是 DOWN，其余全部 UP，但整体状态被拉成 DOWN。

#### 根因

`spring-boot-starter-data-redis` 在 classpath 上，Spring Boot 自动装配了 `RedisHealthIndicator`
并把它计入**顶层**健康状态。而 Redis 在这个应用里只被 `CacheService` 用于缓存——
**缓存不可用不应该是致命的**：应用完全启动成功，对话、检索、工具、记忆全部正常工作
（冒烟测试已验证）。

#### 影响

在 Kubernetes 里，`readinessProbe` 指向 `/actuator/health` 意味着：

- **没有 Redis 的环境里，Pod 永远不会 Ready**，被永久踢出 Service 负载均衡——整个服务不可用，
  哪怕它 99% 的功能完全正常。
- 即使有 Redis，**Redis 抖动一次就会把所有 Pod 同时摘出流量**（readiness）或**重启**（liveness），
  把一次缓存故障放大成一次全站故障。

#### 建议修复

把缓存降级为「不影响健康」：

```yaml
management:
  health:
    redis:
      enabled: false          # 方案一：不把 Redis 计入健康状态
    group:
      readiness:              # 方案二（推荐）：就绪只看真正必需的依赖
        include: db,diskSpace,ping
      liveness:
        include: ping
```

同时在 `CacheService` 上确认缓存读写失败已有降级（失败即穿透到 DB），
并补上对应的容错测试——目前 `CacheService` 行覆盖仅 1.47%。

---

### D9 🟡 工具执行端点字段缺失时静默降级为 `success: true`

**严重度：低** ｜ **证据：`local` profile 冒烟（见第七节）** ｜ **位置：`ToolController:50`**

#### 现象

```bash
# 字段名写错（应为 arguments，实际传了 input）
POST /api/tools/calculator/execute  {"input":"1+2*3"}
→ 200 {"success":true,"toolName":"calculator","result":"无法解析计算表达式"}

# 空 body
POST /api/tools/calculator/execute  {}
→ 200 {"success":true,"toolName":"calculator","result":"无法解析计算表达式"}
```

#### 根因

```java
String arguments = request.getOrDefault("arguments", "").toString();   // :50
```

`@RequestBody Map<String, Object>` + 静默默认值：字段名写错、字段漏传、传了错误类型，
全都无声地变成「用空字符串执行工具」，然后以 `success: true` 返回。
调用方无法区分「工具真的执行了并返回这个结果」和「你的请求有问题」。

#### 影响

面向开发者的调试端点出现这种模糊契约，会把排错时间拉长（我自己就在冒烟时先中招了一次）。
另外 `success` 恒为 true 会让基于它做的自动化断言形同虚设。

#### 建议修复

`arguments` 缺失或非字符串时返回 400：

```java
Object raw = request.get("arguments");
if (!(raw instanceof String arguments) || arguments.isBlank()) {
    return ResponseEntity.badRequest().body(Map.of(
            "success", false, "message", "缺少必填字段 arguments"));
}
```

更彻底的做法是给每个工具定义一个带 `@NotBlank` 的请求 DTO，而不是吃 `Map`。

---

### D10 🔴 浏览器访问时全部接口被内容协商成 **XML**，而非 JSON ｜ ✅ 已于 2026-09-16 修复（第九节）

**严重度：高** ｜ **证据：`local` profile 冒烟（HTTP 实测）** ｜ **位置：`pom.xml` 传递依赖 + 无 `produces` 声明**

#### 现象

同一个接口，仅因 `Accept` 头不同，返回体的**格式**就变了——**包括成功响应**：

```bash
B=http://localhost:8080/api
BROWSER='text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8'

# 浏览器 Accept
curl -H "Accept: $BROWSER" $B/tools
→ 200  content-type: application/xhtml+xml
  <List><item>calculator</item><item>search</item><item>datetime</item><item>weather</item><item>math</item></List>

# 显式 JSON
curl -H "Accept: application/json" $B/tools
→ 200  content-type: application/json
  ["calculator","search","datetime","weather","math"]

# */* 或不带 Accept
curl -H "Accept: */*" $B/tools
→ 200  content-type: application/json
```

错误响应同样被改写（这正是最初暴露问题的现象）：

```bash
curl -H "Accept: $BROWSER" $B/          → 500  application/xhtml+xml
  <ApiError><timestamp>…</timestamp><status>500</status>
   <message>服务器内部错误</message><detail>No static resource .</detail>
   <path>/api/</path></ApiError>

curl -H "Accept: application/json" $B/  → 500  application/json
```

#### 根因

`jackson-dataformat-xml` 经**传递依赖**进入 classpath：

```
dev.langchain4j:langchain4j-milvus:0.36.2
└─ io.milvus:milvus-sdk-java:2.3.9
   └─ com.azure:azure-storage-blob:12.25.3
      └─ com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.18.1   ← 元凶
```

Milvus SDK 为支持 Azure Blob 存储拖进了它。Spring Boot 的 `JacksonHttpMessageConvertersConfiguration`
一旦探测到 `jackson-dataformat-xml`，就会自动注册 `MappingJackson2XmlHttpMessageConverter`，
并且它支持 `application/*+xml` 这一通配——**恰好匹配浏览器 `Accept` 里的 `application/xhtml+xml`**。
浏览器把 `application/xhtml+xml` 和 `application/xml;q=0.9` 排在 `*/*;q=0.8` 之前，
内容协商便选中了 XML 转换器。

`spring-boot-starter-web` 自带的是**纯 JSON** 转换器，且 Spring 默认不做「只允许 JSON」的约束；
项目里没有任何一个 `@RequestMapping` 声明 `produces`，因此无人阻止这件事。

#### 影响

1. **浏览器打开任何一个 GET 端点，看到的是 XML**。我最初收到用户反馈的就是这个现象——
   想调试接口的人会以为后端出了故障，而实际上响应是「正常」的。
2. **两套契约**。文档、测试、前端 fetch 都按 JSON 写；浏览器/Postman 默认头却拿到 XML。
   当时 151 个用例全部用 `MockMvc`（默认 `Accept` 匹配 JSON 路径）跑，
   **没有任何一个用例能发现这个问题**——测试与真实客户端之间隔着这条缝。
   （修复轮次补上了 `ContentNegotiationTest`，专门带真实浏览器 Accept 头把缝堵上。）
3. **错误的定位被污染**。用户复现 D1/D2 时先看到的是 XML 结构，掩盖了真正的问题（500 与措辞泄漏）。
4. 任何 XML 优先的客户端（`Accept: application/xml`）都会被静默切走格式，
   若它按 JSON 解析会直接失败。

#### 建议修复

**首选：排除传递依赖**（本项目根本不用 XML 序列化，排除最干净）：

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-milvus</artifactId>
    <version>${langchain4j.version}</version>
    <exclusions>
        <exclusion>
            <groupId>com.fasterxml.jackson.dataformat</groupId>
            <artifactId>jackson-dataformat-xml</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

> 若 Milvus 后续确需 Azure Blob 能力，可改为排除整个 `com.azure:azure-storage-blob` 子树，
> 或在 `<dependencyManagement>` 中把 `jackson-dataformat-xml` 的 scope 置为 `provided`。

**兜底（建议同时做，与首选不冲突）**：把 XML 转换器直接从转换器链里摘掉，
并且不依赖「classpath 上恰好有什么转换器」：

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 本项目全程 JSON。Milvus SDK 的传递依赖把 Jackson XML 转换器带上了 classpath，
        // 它会匹配浏览器的 application/xhtml+xml，把所有响应（含成功响应）序列化成 XML。
        converters.removeIf(MappingJackson2XmlHttpMessageConverter.class::isInstance);
    }
}
```

> ⚠️ **注意：`configurer.defaultContentType(APPLICATION_JSON)` 不能解决这个问题。**
> `defaultContentType` 只在请求未显式声明可接受类型时（`*/*` 或没有 `Accept`）生效；
> 而浏览器是**显式**列出 `application/xhtml+xml` 的，XML 转换器属于「被主动选中」而非「兜底」，
> 所以默认类型配置在这条路径上根本不参与决策。必须摘掉转换器，或排除依赖。

此外可给 `GlobalExceptionHandler` 标注 `@RestControllerAdvice(produces = MediaType.APPLICATION_JSON_VALUE)`
作为第二层保险（SSE 端点已是 `text/event-stream`，不受影响）。

> 📌 **诚实声明**：以上两种修法**均未在本轮做运行时验证**——本轮交付约定是「只报告、不修主代码」，
> 因此我没有改动 `pom.xml` 或新增 `WebConfig`。根因（传递依赖 + 无 `produces`）
> 和现象（`Accept` 驱动的格式切换）是实测确认的；修复方案是基于 Spring 内容协商机制的推断，
> 落地时请按上面的回归用例验证一次。

**回归防护**：补一个用例，用真实浏览器的 `Accept` 打一个成功端点，
断言 `content-type` 是 `application/json`——这是当前唯一能拦住 D10 复发的测试形式：

```java
mockMvc.perform(get(CONTEXT_PATH + "/tools")
                .header(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .contextPath(CONTEXT_PATH))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
```

---

## 四、验证过但**不是**缺陷的项

专业测试同样要说清「什么被证伪了」，避免把误报提交给开发。

| 曾经的怀疑 | 结论 | 证据 |
| --- | --- | --- |
| 500 响应体会泄漏内部异常细节 | **不成立**。`AgentService` 统一包装了固定文案 | `ChatApiContractTest#internalFailureDoesNotLeakInternals` |
| 认证可被路径穿越绕过 | **不成立**。`/chat/../chat/message` 类路径不返回 200 | `ApiKeySecurityIntegrationTest` |
| 限流可被伪造 `X-Forwarded-For` 绕过 | **不成立**。实现不读该头，按来源 IP 分桶 | `RateLimitIntegrationTest#forwardedForHeaderCannotBypassRateLimit` |
| 限流可被无效密钥绕过（认证前不限流） | **不成立**。限流过滤器先于认证执行 | `RateLimitIntegrationTest#rateLimitAppliesBeforeAuthentication` |
| 清空上下文会连带删除已落库消息 | **不成立**。内存窗口与持久化是两件事 | `ChatContextEndpointTest#clearingMemoryDoesNotDeletePersistedMessages` |
| 校验失败时 SSE 端点会返回 JSON 错误体 | **不成立**。仍保持 `text/event-stream` 并发 `error` 事件 | `StreamingChatSseIntegrationTest#invalidRequestEmitsErrorEventInsteadOfJson` |
| 工具调用异常会导致整轮对话失败 | **不成立**。异常被捕获，状态记录为失败但对话继续 | `ChatFlowIntegrationTest` |
| 中文 JSON 请求体被拒绝（HTTP 400 `Invalid UTF-8 middle byte 0xe3`） | **不成立**。是 Git Bash 把 argv 交给原生 `curl.exe` 时按 GBK 转码所致（GBK 的「你」= `C4 E3`）。改用 `--data-binary @file` 发送真实 UTF-8 后正常进入业务层 | `local` 冒烟，见第七节 |
| multipart 知识库名返回乱码 | **不成立**。用 UTF-8 字节流作表单字段值，响应原样返回 `冒烟知识库UTF8`，编码链路正确 | `local` 冒烟，见第七节 |
| 检索 `sources` 为空说明 RAG 坏了 | **不成立**。当时查询词与知识库内容无关；且向量路因无 API Key 超时而降级，关键词路独立召回正常——正是多路召回容错设计在起作用 | `local` 冒烟，见第七节 |

---

## 五、未覆盖风险清单

按「风险 × 影响面」排序，这些是**本轮之后仍然存在的测试盲区**：

| 类 | 行覆盖 | 风险说明 | 建议 |
| --- | --- | --- | --- |
| `MemoryController` | 1.37% | 长期记忆 CRUD 全无覆盖，但它是用户可见的读写端点 | 补契约测试（复用 `IntegrationTestSupport`，成本很低） |
| `CacheService` | 1.47% | Redis 缓存逻辑未验证。**缓存与 DB 不一致**是这类服务的经典事故点 | 需引入嵌入式 Redis 或对 `RedisTemplate` 打桩 |
| `RetrievalEvaluator` | 2.00% | 评测集是**衡量 RAG 质量的唯一手段**，本身却没有测试 | 用固定数据集断言指标计算正确；建议纳入 CI |
| `ToolController` | 3.33% | 工具直调端点未覆盖 | 契约测试 |
| `MathTool` / `WeatherTool` / `SearchTool` | 6.8% / 8.7% / 10% | 工具实现本身未覆盖（`AgentTools` 只测了 calculator 路径） | 补边界单测（除零、非法表达式、空入参） |
| `DocumentController` | 16.67% | 上传/删除/搜索端点的**成功路径**本轮覆盖到了，但错误分支仍未覆盖 | 结合 D6 的修复一并补 |
| `TokenService` | 29.69% | 记账正确性只在并发缺陷用例里被间接碰到；日期范围/趋势/成本 TopN 未验证 | 补单测，重点覆盖 `calculateCost` 分支 |
| `AgentService` 的模型失败分支 | — | 已验证「不泄漏内部信息」，但**未验证失败时是否会留下半条会话**（用户消息已存、助手消息缺失） | 补一致性断言 |
| 真实 Milvus 路径 | 0% | 所有测试都跑在 `InMemoryEmbeddingStore` 上。**D3 恰恰说明这两条路径语义不同** | 需一个带真实 Milvus 的集成环境（可用 Testcontainers） |
| 真实 OpenAI 调用 | 0% | 桩无法验证 prompt 是否被真实模型正确理解、function calling 是否被正确触发 | 保留 `OPENAI_API_KEY` 的冒烟测试，不纳入常规 CI，定期人工跑 |

---

## 六、工程建议（按优先级）

1. ~~**立刻修 D1 与 D6**。~~ → **均已修复**：D1 连同 D2、D10 见第九节，
   D6 见第十一节（它直接废掉了知识库的增量维护能力，实际改动 5 行）。
2. **部署前必须修 D7 与 D8**。这两个是「CI 全绿但上线即挂」的典型：
   D7 让 Pod 永远不 Ready（没有 Redis 时整个服务不可用），
   D8 让 4 个读端点在关掉 `open-in-view` 的环境里全部 500。
   两者都不会被当前自动化测试发现——测试跑在 H2 且 `open-in-view` 取了默认值。
3. **修 D4 前先加唯一约束**——这是 D4 修复的前提，也是最后一道防线。
4. **D3 需要一次配置口径的统一**，建议以适配器方式解决，顺带检查是否还有其它
   「同一配置项在不同实现下语义漂移」的地方（例如 `MilvusConfig` 的 metric type）。
5. **把 `RetrievalEvaluator` 接入 CI**。现在它有端点、有指标、有评测集，
   但没有任何自动化断言——RAG 系统的质量回归只能靠它来兜。
6. **补上「配置矩阵」测试**。D7 和 D8 的共同根因是「只在某一种配置下被测过」。
   建议至少让集成测试跑两遍：`open-in-view: false` + 关闭 Redis 健康检查，
   与当前默认配置各一次。这比新增几十个用例更能拦住这类缺陷。
7. **给测试加稳定性保护**。并发用例（`BoundaryAndConcurrencyTest`）依赖线程调度，
   已通过「起跑闸门 + 多轮重试」把偶发概率压到可忽略；
   但若接入 CI 后出现 flaky，应优先怀疑这里。

---

## 七、真实启动冒烟测试（`local` profile）

自动化测试跑在 H2 + 模型桩上，为了验证「真实 Spring 上下文 + 真实 HTTP + 真实过滤器链」下
的行为，另做了一轮启动冒烟。

### 7.1 启动

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--management.endpoint.health.show-details=always"
```

```
Tomcat started on port 8080 (http) with context path '/api'
Started AiRagAgentApplication in 17.625 seconds
```

17.6 秒中约 10 秒是等待 Milvus 连接超时（`DEADLINE_EXCEEDED`）后降级到内存向量库——
这是设计内的降级路径，日志有明确告警：`Milvus 不可用，回退内存向量库`。
本机 Redis(6379) / MySQL(3306) 均未运行，应用照常启动（两者都是懒连接）。

### 7.2 端点矩阵

| 端点 | 结果 | 说明 |
| --- | --- | --- |
| `GET /actuator/health` | ⚠️ **503 DOWN** | → **D7**（Redis 拖垮整体健康） |
| `GET /chat/context/{sid}` | ✅ 200 | 空上下文返回 `messageCount: 0`，不是 404 |
| `GET /chat/tokens/{sid}` | ✅ 200 | 估算三项为 0 |
| `POST /chat/message` | ⚠️ 500 | 无有效 `OPENAI_API_KEY`（超时），错误体是统一 `ApiError`、不泄漏内部细节 |
| `POST /chat/stream` | ✅ **200 + `text/event-stream`** | 事件序列 `sources` → `error{"message":"timeout"}`；以 error 收尾而非静默断流，契约正确 |
| `GET /documents/knowledge-bases` | ❌ **500** | → **D8**（LazyInitialization） |
| `GET /documents/knowledge-bases/{id}` | ❌ **500** | → **D8** |
| `GET /documents/knowledge-bases/{id}/documents` | ❌ **500** | → **D8** |
| `GET /documents/search?query=` | ❌ **500** | → **D8** |
| `GET /documents/search/detailed?query=` | ✅ 200 | 返回 `source: "keyword"`、`keywordRank: 1` |
| `GET /documents/knowledge-bases/{id}/stats` | ✅ 200 | |
| `POST /documents/upload`（首次） | ✅ 200 | `documentCount: 1` |
| `POST /documents/upload`（同知识库第二次） | ❌ **500** → ✅ **200** | → **D6** 线上复现，已修复（第十一节） |
| `GET /tools` | ✅ 200 | 5 个工具（**但浏览器 Accept 下是 XML**，见 7.5 → **D10**） |
| `POST /tools/{name}/execute`（calculator/math/datetime/search） | ✅ 200 | 结果正确，如 `计算结果：1+2*3 = 7.000000` |
| `POST /tools/calculator/execute`（字段名写错 / 空 body） | ⚠️ 200 + `success:true` | → **D9** |
| `POST /memory/save` + `GET /memory/{sid}/{key}` | ✅ 200 | |
| `GET /evaluation/info` / `POST /evaluation/retrieval` | ✅ 200 | 6 条用例，输出 recall/precision/MRR/hitRate |
| `GET /chat/message`（G0 打 POST 端点） | ❌ **500** | → **D1** 线上复现（应为 405） |
| `GET /definitely-not-exists` | ❌ **500** | → **D1 + D2** 线上复现（应为 404，且 detail 泄漏 `No static resource ...`） |

**D1 / D2 / D6 在真实 HTTP 下逐条复现**，与自动化测试的断言完全一致——这说明
「缺陷快照测试」的结论是对的，不是 mock 环境造成的假象。
（三者现已全部修复：D1 / D2 见第九节，D6 见第十一节。）

### 7.3 关于向量路降级（一个正向结论）

冒烟时 `source` 恒为 `keyword`，日志给出原因：

```
WARN c.a.r.service.RagService - 向量召回失败，本路降级为空：java.io.InterruptedIOException: timeout
```

OpenAI embedding 调用超时（无 API Key / 无外网），向量路整体降级为空，
但**关键词路仍独立返回结果，对话链路不受影响**。这正是 Stage 2 多路召回「每路失败只降级该路」
的设计目标，在真实环境下得到了验证。

> 反过来说，这也印证了 **D3** 的现实意义：降级路径不是理论上的兜底，
> 而是**会被真实走到**的生产路径——那么它在 `min-score` 语义上的偏差就必须修。

### 7.4 编码问题的澄清（记录以免误判）

冒烟过程中两处「看起来像缺陷」的现象，追查后确认都是**客户端工具链问题**，不是应用缺陷：

1. **中文 JSON body 返回 400** `JSON parse error: Invalid UTF-8 middle byte 0xe3`。
   根因：Git Bash 把命令行参数交给原生 `curl.exe` 时按 Windows ANSI 代码页（GBK）转码，
   GBK 的「你」= `C4 E3`，`0xE3` 正是被解码器报出的那个非法续字节。
   改用 `curl --data-binary @utf8-file` 后请求正常进入业务层。**应用解析 UTF-8 JSON 是正确的。**
2. **multipart 知识库名乱码**。同样走 argv 转码路径。改用
   `curl -F "knowledgeBaseName=<utf8-file"` 后，响应原样返回 `冒烟知识库UTF8`。

结论：**通过 Windows 控制台用 curl 发中文时，必须把 body/字段值放进文件传输**，
否则会得到误导性的 400。

### 7.5 D10 的现场证据（内容协商）

用户最初贴出的报错本身就是 D10 的证据——那段 `<ApiError>` **是 XML**。顺着它实测：

```bash
B=http://localhost:8080/api
BROWSER='text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8'

echo "=== 成功端点 /tools ==="
curl -s -o /dev/null -w "浏览器 Accept  -> %{http_code}  %{content_type}\n" -H "Accept: $BROWSER" $B/tools
curl -s -o /dev/null -w "application/json -> %{http_code}  %{content_type}\n" -H "Accept: application/json" $B/tools
curl -s -o /dev/null -w "*/*             -> %{http_code}  %{content_type}\n" -H "Accept: */*" $B/tools

echo "=== 错误端点 /api/ ==="
curl -s -o /dev/null -w "浏览器 Accept  -> %{http_code}  %{content_type}\n" -H "Accept: $BROWSER" $B/
curl -s -o /dev/null -w "application/json -> %{http_code}  %{content_type}\n" -H "Accept: application/json" $B/

echo "=== 浏览器 Accept 下的响应体 ==="
curl -s -H "Accept: $BROWSER" $B/tools
```

实测输出：

```
=== 成功端点 /tools ===
浏览器 Accept  -> 200  application/xhtml+xml
application/json -> 200  application/json
*/*             -> 200  application/json
=== 错误端点 /api/ ===
浏览器 Accept  -> 500  application/xhtml+xml
application/json -> 500  application/json
=== 浏览器 Accept 下的响应体 ===
<List><item>calculator</item><item>search</item><item>datetime</item><item>weather</item><item>math</item></List>
```

结论：**不只是错误响应，成功响应也被 XML 化了**——这是全局内容协商问题，不是异常处理问题。
传递依赖来源已用 `mvn dependency:tree` 定位到
`milvus-sdk-java → azure-storage-blob → jackson-dataformat-xml`（见 D10 根因）。

---

## 八、附：如何复现

```bash
export JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
export PATH="/c/Users/MECHREVO/tools/apache-maven-3.9.16/bin:$PATH"

cd "E:/PyCharm/AIexe-java"

# 全量测试
mvn test

# 带覆盖率
mvn clean test jacoco:report
# 报告位置：target/site/jacoco/index.html

# 只看缺陷证据
mvn test -Dtest='ErrorHandlingContractTest,BoundaryAndConcurrencyTest'
```

所有测试**不需要 Milvus / MySQL / Redis / OpenAI API Key**，
直接跑在 H2 内存库 + 模型桩上，本地与 CI 均可执行。

---

## 九、修复轮次（2026-09-16）：D1 / D2 / D10

修复范围限定在**「HTTP 契约」这一层**——即「同一个请求，客户端拿到的状态码与格式是否正确」。
这三条是唯一在真实浏览器访问时**必然**被撞到的缺陷，且改动小、风险低、可回滚。
其余 7 个（D3~D9）涉及存储语义、并发与配置矩阵，需要单独评估，本轮未动。
（后续轮次已修掉其中的 D7 / D8，见第十节；D6，见第十一节。）

### 9.1 改动清单

| 文件 | 改动 | 对应缺陷 |
| --- | --- | --- |
| `pom.xml` | `langchain4j-milvus` 增加 `<exclusion>` 排除 `jackson-dataformat-xml` | **D10** |
| `exception/GlobalExceptionHandler.java` | 为 6 类框架异常补显式处理器；兜底分支不再回填 `e.getMessage()` | **D1 / D2** |
| `controller/ErrorHandlingContractTest.java` | 由「缺陷快照」翻转为正向契约断言（9 个用例） | D1 / D2 回归 |
| `controller/ContentNegotiationTest.java` | **新增**，带真实浏览器 Accept 头的内容协商防护（3 个用例） | D10 回归 |

### 9.2 D1 / D2 的修法

在 `GlobalExceptionHandler` 中为框架异常补上显式处理器。原因是
`@RestControllerAdvice` 的优先级**高于** `DefaultHandlerExceptionResolver`，
所以 `@ExceptionHandler(Exception.class)` 会把 Spring MVC 自己的框架异常一并捞走，
把客户端错误降级成服务端错误。新增的映射：

| 异常 | 修复前 | 修复后 |
| --- | --- | --- |
| `NoResourceFoundException` / `NoHandlerFoundException` | 500 | **404** |
| `HttpRequestMethodNotSupportedException` | 500 | **405**（并告知支持的方法） |
| `HttpMediaTypeNotSupportedException` | 500 | **415** |
| `HttpMediaTypeNotAcceptableException` | 500 | **406** |
| `MissingServletRequestParameterException` | 500 | **400** |
| `MethodArgumentTypeMismatchException` | 500 | **400** |
| 其它 `Exception`（兜底） | 500，detail = `e.getMessage()` | 500，**固定文案** |

D2 的修法是兜底分支不再回填 `e.getMessage()`——原始消息可能带 SQL、连接串、类名。
完整堆栈仍以 ERROR 级别落日志，排查能力不受影响。

### 9.3 D10 的修法

在 `pom.xml` 里排除传递依赖 `jackson-dataformat-xml`。转换器不再被 Spring Boot 自动注册，
`application/*+xml` 这条通配路径随之消失，内容协商回到恒定 JSON。

> **为什么不用 `spring.mvc.contentnegotiation.default-content-type=application/json`？**
> 该属性只在请求**未**显式声明可接受类型（Accept 为通配符或缺失）时生效。
> 而浏览器是把 `application/xhtml+xml` **显式**列出来的，
> XML 转换器属于「被主动选中」而非「兜底命中」——默认类型配置在那条路径上根本不参与决策。
> 这一点在 `ContentNegotiationTest` 的类注释里也记了一笔，避免后人再走这条弯路。

### 9.4 验证结果

**自动化**：155 个用例全绿（修复前 151，新增 4 个）。覆盖率行 59.89% → **60.08%**。

**真实 HTTP**（`local` profile，实际启动 + curl）：

| 验证项 | 修复前 | 修复后 |
| --- | --- | --- |
| `GET /api/tools`，浏览器 Accept | `200 application/xhtml+xml`，`<List><item>calculator</item>…` | **`200 application/json`**，`["calculator","search","datetime","weather","math"]` |
| `GET /api/definitely-not-exists`，浏览器 Accept | `500` + `No static resource .` | **`404`** + `路径 /api/definitely-not-exists 未匹配到任何端点` |
| `GET /api/chat/message`（只支持 POST） | `500` | **`405`** |
| `POST /api/chat/message` + `text/plain` | `500` | **`415`** |
| `GET /api/tools`，`Accept: application/json` | `200 application/json` | `200 application/json`（未回归） |

### 9.5 未验证 / 遗留

- **只接受 XML 的客户端现在会拿到 406**（而非静默的 XML）。这是正确的 HTTP 语义，
  但若有未知的 XML 消费方，属于**行为变更**，需要确认没有这样的调用方。
- `azure-storage-blob` 的 Azure Blob 批量导入能力随之不可用。本项目未使用 Milvus 的
  bulk writer，测试与启动均未出现 `NoClassDefFoundError`；但**未在真实 Milvus 集群上验证过**。
- D10 的修复**只保证了响应格式**。若后续有人重新引入任何 XML 序列化依赖，
  `ContentNegotiationTest` 会立刻失败——这是本轮留下的主要防线。

---

## 十、修复轮次二（2026-09-16）：D7 / D8 + 根路径入口

第二轮的目标从「HTTP 契约正确」转向**「开箱可用」**：让 `local` profile 下
浏览器打开每一个非模型接口都能拿到 200，不需要 Docker、不需要 MySQL/Redis/Milvus。

### 10.1 D8：4 个读端点必 500（LazyInitialization）

**根因**：`KnowledgeBase` 上有 `@OneToMany(fetch = LAZY) List<Document> documents`，
`Document` 上有 `@ManyToOne(fetch = LAZY) KnowledgeBase knowledgeBase`。
控制器直接返回实体，Jackson 序列化去调这些 getter 时事务已提交、Session 已关闭
（`open-in-view: false`），于是抛 `LazyInitializationException`。

**修法**：新增 `KnowledgeBaseSummary` / `DocumentSummary` 两个 record DTO，
4 个端点改为返回 DTO。**不采用**「打开 open-in-view」或「加 `@JsonIgnore`」——
API 契约不应随实体结构漂移：实体加一个关联字段就可能改变响应体甚至打挂接口。

| 端点 | 修复前 | 修复后 |
| --- | --- | --- |
| `GET /documents/knowledge-bases` | 500 | **200** |
| `GET /documents/knowledge-bases/{id}` | 500 | **200** |
| `GET /documents/knowledge-bases/{id}/documents` | 500 | **200** |
| `GET /documents/search?query=` | 500 | **200** |

顺带把 `KnowledgeBase.filePath`（服务端本地路径，内部信息）从响应里去掉；
`DocumentSummary` 的关联只暴露 `knowledgeBaseId` 主键，不再整个对象序列化出去。

### 10.2 为什么 160 个用例之前一个都没发现 D8

这是本轮最值得记的一点。测试上下文的 `spring.jpa.open-in-view` 取了 Spring Boot 的
**默认值 `true`**：Session 被拖到响应渲染之后才关，懒加载「恰好」能工作。
而 `application-local.yml` 里是 `false`。**同一份代码，两种配置，一边全绿一边必挂。**

修法不只是改端点，还要把这个盲区堵死 —— 已在 `IntegrationTestSupport` 显式设置
`spring.jpa.open-in-view=false`，与生产对齐。今后任何「返回实体 + 序列化懒关联」
的写法都会在测试里直接失败。

> 这正是第六节第 6 条「配置矩阵」建议的落地：**比新增几十个用例更能拦住这类缺陷**。

### 10.3 D7：没有 Redis 时 health 不 Ready

**根因**：Spring Boot 默认把 `RedisHealthIndicator` 计入整体健康，本机不跑 Redis
→ `/actuator/health` 恒为 503 DOWN。在 K8s 里这意味着 **Pod 永远不 Ready**，
而真正要命的不是「Redis 挂了」，是「服务其实能跑，却被一个可选依赖拖垮了」。

**修法**：`application-local.yml` 中 `management.health.redis.enabled=false`。
本项目里 Redis 确实是可选依赖（懒连接 + 缓存不可用走库），本地关掉是合理的。
生产若把 Redis 当必需组件则应保持开启，并改用 health group 区分
liveness / readiness，而不是让 `/health` 一票否决。

### 10.4 根路径入口

`/api/` 此前没有任何映射 —— 修复 D1 之前返回 500，之后返回 404。
调试的人第一反应就是开根路径，拿到 404 很容易误判成「服务没起来」。
新增 `ApiIndexController`，`GET /` 返回一份端点清单（JSON）。
刻意返回 JSON 而非 HTML：本服务是纯 API，HTML 落地页会暗示它有人机界面。

### 10.5 验证结果

**自动化**：160 个用例全绿（修复前 155，新增 `DocumentReadEndpointTest` 5 个）。
覆盖率：行 **60.08% → 62.58%**、指令 60.44% → 62.88%、方法 68.99% → 71.74%、
分支 56.52% → 56.50%（持平）。

**真实 HTTP**（`local` profile，实际启动 + curl）：

| 验证项 | 修复前 | 修复后 |
| --- | --- | --- |
| `GET /api/` | 404（更早是 500） | **200** + 端点清单 JSON |
| `GET /api/actuator/health` | 503 DOWN | **200 `{"status":"UP"}`** |
| `GET /api/documents/knowledge-bases` | 500 | **200** + DTO 数组 |
| `GET /api/documents/knowledge-bases/1` | 500 | **200** |
| `GET /api/documents/knowledge-bases/1/documents` | 500 | **200** |
| `GET /api/documents/search?query=RRF` | 500 | **200**，实际命中 1 条 |

上传 → 落库 → 读回 走的是真实 multipart 链路，确认 DTO 里的
`knowledgeBaseId`、`chunkId`、`vectorId` 都正确回填。

### 10.6 D11：用过工具的会话查上下文必 500

**这是在接入真实 DeepSeek 端点做端到端验证时才暴露的缺陷**，记在这里是因为它的
发现方式本身说明了一件事：**桩模型 + MockMvc 覆盖不到「真实模型的行为细节」**。

**现象**：任何一个触发过工具调用的会话，`GET /chat/context/{sessionId}` 必 500：

```
java.lang.NullPointerException: null
    at java.util.ImmutableCollections$MapN.<init>
    at java.util.Map.of(Map.java:1373)
    at com.ai.rag.controller.ChatController.lambda$getContext$0(ChatController.java:148)
```

**根因**：`ChatController:148` 用 `Map.of("role", ..., "content", m.text())` 组装响应，
而 **`Map.of` 的键和值都不允许为 null**。`AiMessage.text()` 在
「**只发起工具调用、没有文本内容**」时正好返回 null —— 这正是模型决定调工具的那一条消息。
于是只要用过一次工具，上下文接口就永久 500。

`/chat/tokens/{sessionId}` 有同样的隐患（把 `msg.text()` 直接传给 `TokenCounter`）。

**为什么测试没发现**：既有的 `ChatContextEndpointTest` 只覆盖纯文本对话，
`AiMessage.text()` 一直非 null；而 MockMvc 测试里要让模型发起工具调用，
必须显式压入 `replyWithToolCall`，此前没人这么组合过。
**用桩不会自动产生「工具调用 + 无文本」这条消息组合——它是真实模型的行为。**

**修法**：`content` 做 null 兜底；并把工具调用透出到 `toolCalls` 字段 ——
否则上下文里那条记录内容为空，看不出发生过什么，排错时很误导。

```json
{"role":"AI","content":"","toolCalls":[{"name":"calculator","arguments":"{\"expression\": \"1+2*3\"}"}]}
```

### 10.7 端到端验证结果（真实 DeepSeek 端点）

用 `OPENAI_BASE_URL=https://api.deepseek.com` + `OPENAI_MODEL=deepseek-chat`
接入真实模型，逐项验证：

| 验证项 | 结果 |
| --- | --- |
| 单轮对话 | ✅ `{"response":"收到","inputTokens":529,"outputTokens":1,...}` |
| 多轮记忆 | ✅ 追问「我刚才让你回复的是哪两个字？」正确答出「收到」 |
| 工具调用 | ✅ `toolCalls[0]` = calculator，`toolOutput` = `计算结果：1+2*3 = 7.000000`，`status` = SUCCESS |
| SSE 流式 | ✅ `sources` → 7×`token` → `done`，且 `tokensEstimated: false`（真实用量，非估算） |
| 会话上下文 | ✅ 修复 D11 后 200，4 条消息含工具调用明细 |
| RAG 检索 | ✅ 上传文档后提问「RAG 用什么融合排序算法」正确答出「RRF」 |

### 10.8 一个必须说明的限制：DeepSeek 下只有关键词检索

`LangChain4jConfig` 里 `embeddingModel()` 与 `chatLanguageModel()` **共用同一个
`baseUrl` 和 `apiKey`**。而 **DeepSeek 不提供 embedding 接口**，所以：

```
ERROR OpenAiEmbeddingModel - ... 404
  at DocumentService.saveVectorToEmbeddingStore(DocumentService.java:169)
INFO  RagService - Multi-recall fused: vector=0, keyword=1, returned=1, topSource=keyword
```

**向量路恒为 0，检索完全由关键词路承担。** 多路召回的容错设计在这里体现得很好
（上传不失败、检索仍有结果），但**这不是真正的向量 RAG**，语义相近但用词不同的查询召不回来。

若要修，需要给 embedding 单独配一套 `base-url`/`api-key`（约 10 行配置 + 2 个 `@Value`），
指向 OpenAI、硅基流动、阿里百炼等提供 embedding 的服务。

### 10.9 仍未修

**D3**（降级向量库 score 口径）、**D4**（并发重复会话）、**D5**（并发 token 记账）、
**D9**（工具端点静默 `success: true`）。~~D6~~ 已在第十一节修复。

---

## 十一、修复轮次三：D6（知识库增量维护）

### 11.1 根因回顾

`chunk_id` 由「知识库 ID + **本次上传内**的分块序号」构成：

```java
document.setChunkId(generateChunkId(knowledgeBase.getId(), i));   // DocumentService:129
// → "kb_5_chunk_0" / "kb_5_chunk_1" / ...
```

第二次往同一知识库上传时 `i` 从 0 重新开始，必然重复插入 `kb_5_chunk_0`，
撞上 `UNIQUE (knowledge_base_id, chunk_id)`，`@Transactional` 让整个上传回滚。
**一个知识库名字只能用一次。**

### 11.2 修法

给每次上传生成一个文档身份，`chunk_id` 从「知识库维度」改为「上传批次维度」：

```java
// saveDocumentsWithVectors 开头
String documentId = UUID.randomUUID().toString().replace("-", "");
...
document.setChunkId(generateChunkId(documentId, i));

private String generateChunkId(String documentId, int chunkIndex) {
    return String.format("doc_%s_chunk_%d", documentId, chunkIndex);
}
```

**共 5 行**（含 import 无需新增——`UUID` 本就已引入）。

选「加 UUID 前缀」而不是「把 `created_at` 加进唯一约束」的理由：
`chunk_id` 本就该全局唯一，改前缀后同一知识库可反复追加、多文件入库，
且每个分块能直接追溯到它来自哪一次上传（`doc_<uuid>_chunk_<i>`）。
唯一约束与列宽（`VARCHAR(100)`）都无需改动。

### 11.3 验证

**两条缺陷快照测试翻转为正向契约断言**，并新增一条可追溯性断言：

| 用例 | 断言 |
| --- | --- |
| `secondUploadToSameKnowledgeBaseSucceeds` | 第二次上传 200、`documentCount: 1`、`BETA-2000` 真的进库，且两批内容**都能被检索到** |
| `chunkIdIsUniqueAcrossUploadsAndTraceable` | `chunk_id` 跨两次上传**不重复**、都形如 `doc_..._chunk_0`，且两次上传的前缀（文档身份）**互不相同** |

**先验证用例能抓到缺陷**：把 `DocumentService.java` 暂时 `git stash` 回旧版本后单独运行这两条，
结果如预期失败 ——

```
secondUploadToSameKnowledgeBaseSucceeds  Status expected:<200> but was:<500>
chunkIdIsUniqueAcrossUploadsAndTraceable  indexDocument:408 ? Runtime  Failed to process document
Tests run: 2, Failures: 1, Errors: 1
```

恢复修复后再跑，162 个用例全绿。**这两条不是「跟着实现写」的测试，是真的能红。**

顺带清掉了 `resultCountIsBoundedByTopK` 里为绕开 D6 而把两条内容拆进两个知识库的权宜写法
——现在两条内容写进**同一个**知识库，才真正测到「同一知识库下 topK 是否生效」。

**真实 HTTP 复验**（重启 `local` profile 后，对同一个知识库连传三次）：

```
POST /api/documents/upload  → 200  {"success":true,"documentCount":1,"knowledgeBaseId":1}
POST /api/documents/upload  → 200  {"success":true,"documentCount":1,"knowledgeBaseId":1}
POST /api/documents/upload  → 200  {"success":true,"documentCount":1,"knowledgeBaseId":1}
```

三条记录的 `chunkId` 互不相同，都形如 `doc_<uuid>_chunk_0`：

```
doc_1e44a04c980f4bf7aa9a4788e18fdbf4_chunk_0
doc_745101532f2e45df9be1e6266b00fc8f_chunk_0
doc_b3bc52f1662e4a6298c42529b058eafb_chunk_0
```

`GET /documents/search/detailed` 的召回也是精确的（第 1、3 条同为第一份文件的内容）：

| 查询 | 命中 |
| --- | --- |
| `ALPHA-1000` | 2 条（`keywordRank` 1、2），均来自第一份文件 |
| `BETA-2000` | 1 条，来自第二份文件 |

### 11.4 未做

`DocumentController` 上传失败的响应仍是 `500 + "文档上传失败：Failed to process document"`，
根因只在服务端日志里。**本轮有意不动**——D6 修好后这条路径已不再必然触发，
而改文案是「客户端该看到多少内部信息」的取舍，应统一设计成「可读的领域错误 + 409」，
而不是在这里单独放宽（D2 已确立「不泄漏内部措辞」的原则）。

