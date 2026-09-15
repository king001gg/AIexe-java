# AI RAG Agent · 企业级智能问答系统

基于 **Java 17 / Spring Boot 3.4 / LangChain4j / Milvus / Redis** 构建的 RAG 智能问答 Agent。

支持多格式文档解析与向量化入库、多路召回 + RRF 融合检索、Function Calling 工具调用、SSE 流式输出、
API Key 认证与限流、Flyway 版本化迁移，以及 Prometheus + Grafana 全链路可观测性。

## ✨ 核心功能

### RAG 智能问答
- **多格式文档解析**：支持 PDF / Word（.doc / .docx）/ Excel（.xls / .xlsx）/ 纯文本（.txt / .md）
- **智能分块**：基于段落与句子边界的分块策略，支持重叠窗口
- **向量化入库**：LangChain4j Embedding + Milvus 向量数据库（HNSW 索引，COSINE 度量）
- **多路召回 + RRF 融合**：向量语义召回与关键词稀疏召回并行，用倒数排名融合（Reciprocal Rank
  Fusion）合并两路结果 —— 避免余弦相似度与关键词命中分数尺度不同、无法直接加权的问题。
  任一路失败只降级该路，不影响另一路
- **优雅降级**：Milvus 不可用时自动回退到内存向量库，应用仍可启动

### Agent 能力
- **LangChain4j `AiServices`**：以接口 + 注解声明式装配对话能力，Function Calling 由框架驱动
- **5 个内置工具**：计算器、天气查询、数学函数、知识库检索、日期时间
- **真实 Token 计量**：从模型响应读取真实用量，缺失时回退估算并标记 `tokensEstimated`
- **SSE 流式输出**：逐 token 推送，事件类型 `token` / `tool` / `sources` / `done` / `error`

### 对话记忆
- **LangChain4j `ChatMemory`**：按 sessionId 维护滑动窗口上下文
- **会话与消息落库**：会话、消息、工具调用、Token 用量全部持久化到 MySQL
- **长期记忆注入**：从记忆表读取用户画像并按轮次注入提示词

### 企业级工程化
- **认证**：API Key（`X-API-Key` 请求头），常数时间比较，支持多密钥与免认证白名单
- **限流**：按调用方维度的令牌桶，支持突发容量，超限返回 429 + `Retry-After`
- **数据库迁移**：Flyway 版本化管理，按数据库厂商隔离方言（`{vendor}`）
- **检索评测**：内置 Recall@K / Precision@K / MRR / HitRate 评测端点，作为调参回归基线
- **可观测性**：Prometheus + Grafana 监控 Agent 全链路耗时与 Token 消耗
- **缓存**：Redis 多级缓存（记忆 / 会话 / 知识）
- **配置管理**：多环境配置（local / dev / test）

## 🛠 技术栈

| 组件 | 技术 |
| --- | --- |
| 语言 | Java 17 |
| 框架 | Spring Boot 3.4 |
| AI 框架 | LangChain4j 0.36.2 |
| 向量数据库 | Milvus 2.3.6 |
| 安全 | Spring Security（API Key 过滤器） |
| 数据库迁移 | Flyway |
| 缓存 | Redis 7 |
| 数据库 | MySQL 8.0（本地/测试用 H2） |
| 文档解析 | Apache POI + PDFBox |
| 监控 | Prometheus + Grafana |

> **为什么锁定 LangChain4j 0.36.2**：1.x 的 `langchain4j-spring-boot-starter` 与
> `langchain4j-milvus` 仍为 beta；0.36.2 是最后一个全稳定版本。该版本没有 `@AiService`
> 注解，因此本项目使用编程式 `AiServices.builder(...)` 装配。

## 📁 项目结构

```
AIexe-java/
├── src/main/java/com/ai/rag/
│   ├── AiRagAgentApplication.java      # 启动类
│   ├── config/                         # 配置类
│   │   ├── LangChain4jConfig.java      # LLM / Embedding / AiServices 装配
│   │   ├── MilvusConfig.java           # 向量库配置 + 内存库降级
│   │   ├── SecurityConfig.java         # 安全过滤链、API Key、限流注册
│   │   ├── RedisConfig.java            # 缓存配置
│   │   ├── LoggingConfig.java          # 日志配置
│   │   └── ToolRegistryConfig.java     # 工具注册
│   ├── security/                       # 认证与限流
│   │   ├── ApiKeyAuthFilter.java       # API Key 认证过滤器
│   │   ├── ApiKeyProperties.java       # 密钥配置与常数时间校验
│   │   ├── JsonAuthenticationEntryPoint.java  # 401 JSON 响应
│   │   ├── RateLimitFilter.java        # 令牌桶限流过滤器
│   │   ├── RateLimitProperties.java    # 限流配置
│   │   └── ErrorResponseWriter.java    # 过滤器层统一错误体
│   ├── controller/                     # REST API
│   │   ├── ChatController.java         # 聊天（阻塞 + SSE 流式）
│   │   ├── DocumentController.java     # 文档上传/检索
│   │   ├── EvaluationController.java   # 检索评测
│   │   ├── ToolController.java         # 工具接口
│   │   └── MemoryController.java       # 记忆接口
│   ├── exception/
│   │   └── GlobalExceptionHandler.java # 统一异常 -> ApiError
│   ├── service/
│   │   ├── Assistant.java              # AiServices 接口（chat / stream）
│   │   ├── AgentService.java           # 非流式对话主流程
│   │   ├── StreamingChatService.java   # SSE 流式对话
│   │   ├── ConversationService.java    # 会话与消息落库
│   │   ├── MemoryPromptBuilder.java    # 长期记忆注入
│   │   ├── RagService.java             # 多路召回 + RRF 融合
│   │   ├── TokenUsageResolver.java     # 真实用量 / 估算回退
│   │   ├── TokenService.java           # Token 统计
│   │   ├── DocumentService.java        # 文档处理
│   │   ├── CacheService.java           # 缓存管理
│   │   ├── ToolExecutor.java           # 工具接口
│   │   ├── retrieval/
│   │   │   ├── RrfFusion.java          # 纯函数 RRF 融合
│   │   │   └── HybridContentRetriever.java  # 对话链路接入混合检索
│   │   └── evaluation/
│   │       ├── RetrievalEvaluator.java # 评测运行器
│   │       ├── RetrievalMetrics.java   # 指标计算（纯函数）
│   │       └── RelevanceMatcher.java   # 相关性判定（纯函数）
│   ├── agent/tools/                    # 工具实现
│   │   ├── AgentTools.java             # @Tool 包装 + 调用落库
│   │   ├── CalculatorTool.java         # 计算器
│   │   ├── WeatherTool.java            # 天气查询
│   │   ├── MathTool.java               # 数学函数
│   │   ├── SearchTool.java             # 知识库检索
│   │   └── DateTimeTool.java           # 日期时间
│   ├── repository/                     # 数据访问层
│   ├── model/
│   │   ├── entity/                     # JPA 实体
│   │   └── dto/                        # 传输对象（含 ApiError / RetrievalHit / Evaluation*）
│   └── util/
│       ├── TokenCounter.java           # Token 计数与成本计算
│       ├── TokenBucket.java            # 令牌桶限流器
│       └── DocumentParser.java         # 文档解析
├── src/main/resources/
│   ├── application.yml                 # 主配置（含 dev / test profile）
│   ├── application-local.yml           # 本地配置（H2，关闭认证与限流）
│   ├── db/migration/mysql/V1__init.sql # MySQL 迁移脚本
│   ├── db/migration/h2/V1__init.sql    # H2 迁移脚本
│   └── evaluation/retrieval-cases.json # 检索评测数据集
├── src/test/java/                      # 单元测试 + 迁移校验测试
├── grafana/                            # Grafana 配置
├── prometheus.yml / alert_rules.yml    # Prometheus 与告警配置
├── Dockerfile / docker-compose.yml     # 容器化部署
└── pom.xml
```

## 🚀 快速开始

### 1. 环境要求

- JDK 17+
- Maven 3.9+
- Docker & Docker Compose（可选）

### 2. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env，填入 OPENAI_API_KEY 等配置
```

### 3. 启动基础设施（Docker）

```bash
docker compose up -d mysql redis milvus etcd minio
```

> 首次启动 MySQL 时需先创建库（Flyway 连接的目标库必须已存在）：
> `CREATE DATABASE ai_rag_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;`
> 表结构由 Flyway 在应用启动时自动创建，无需手工建表。

### 4. 构建并启动应用

```bash
mvn clean package
java -jar target/ai-rag-agent-1.0.0.jar
```

本地无 MySQL / Milvus / Redis 时，可用 `local` profile 以 H2 内存库直接启动：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### 5. 启动监控（可选）

```bash
docker compose up -d prometheus grafana
```

访问：
- 应用 API：<http://localhost:8080/api>
- Grafana：<http://localhost:3000>（默认 admin/admin）
- Prometheus：<http://localhost:9090>

## ⚙️ 关键配置

```yaml
security:
  enabled: true                 # 本地 local profile 默认 false
  header: X-API-Key
  api-keys:
    - name: default
      key: ${API_KEY:dev-api-key-change-me}
  permit-all:
    - /actuator/health
    - /actuator/info
    - /error

rate-limit:
  enabled: true                 # 本地 local profile 默认 false
  requests-per-minute: 60       # 令牌补充速率
  burst: 10                     # 突发容量

rag:
  retrieval:
    vector-top-k: 20            # 向量路召回深度
    keyword-top-k: 20           # 关键词路召回深度
    min-score: 0.3              # 向量召回最低相似度
    rrf-k: 60                   # RRF 平滑常数
    final-top-k: 5              # 融合后注入对话上下文的分块数
```

## 🔌 REST API

> **认证**：`security.enabled=true` 时，除白名单外的所有请求都必须携带 `X-API-Key` 请求头，
> 否则返回 `401`；超出限流阈值返回 `429` 并带 `Retry-After`。
> `local` profile 默认关闭认证与限流，便于本地调试。

```bash
# 所有请求都带上密钥
curl -H "X-API-Key: $API_KEY" http://localhost:8080/api/...
```

### 聊天

```bash
POST /api/chat/message
{
  "message": "你好",
  "sessionId": "session-123",
  "useTools": true,
  "enabledTools": ["calculator", "weather", "math", "search", "datetime"]
}
```

### 流式聊天（SSE）

```bash
POST /api/chat/stream
{
  "message": "介绍一下 Milvus",
  "sessionId": "session-123"
}
```

事件流依次为 `sources`（检索来源）→ `token`（增量文本）→ `tool`（工具调用）→
`done`（完整回答、真实 token 用量、成本、conversationId），异常时为 `error`。

### 文档上传

```bash
POST /api/documents/upload
# multipart/form-data
# file: 文档文件
# knowledgeBaseName: 知识库名称
```

### 知识库检索

```bash
GET /api/documents/search?query=关键词&topK=5
# 带分数与各路排名的详细结果：
GET /api/documents/search/detailed?query=关键词&topK=5
```

### 检索评测

```bash
POST /api/evaluation/retrieval
{
  "dataset": "classpath:evaluation/retrieval-cases.json",
  "knowledgeBaseId": null,
  "topK": 5
}
# 查看当前生效的数据集
GET /api/evaluation/dataset
```

返回 `recallAtK` / `precisionAtK` / `mrr` / `hitRate` 汇总指标与逐用例明细（含 `missing`
字段标出漏召的 ground truth）。

### 工具执行

```bash
POST /api/tools/{toolName}/execute
{
  "arguments": "{\"expression\": \"1+2\"}"
}
```

### 记忆管理

```bash
POST /api/memory/save
{
  "sessionId": "session-123",
  "key": "用户姓名",
  "value": "张三"
}
```

## 🗄 数据库迁移

表结构由 **Flyway** 版本化管理，是 schema 的唯一真实来源（`ddl-auto: none`，JPA 不再改表）。

- 脚本位置：`src/main/resources/db/migration/{vendor}/`，其中 `{vendor}` 由 Spring Boot 解析为
  实际数据库厂商（`mysql` / `h2`）。两种方言需要分开维护，因为 H2 不支持 `ENGINE`、
  建表内联 `INDEX`、`ON UPDATE CURRENT_TIMESTAMP` 等 MySQL 语法。
- 新增变更请添加 `V2__xxx.sql`，**不要修改已发布的 V1**（Flyway 校验和会失配）。
- `baseline-on-migrate: true`：兼容由早期 `ddl-auto: update` 建起来的存量库。
- 迁移脚本与 JPA 实体的一致性由 `MigrationSchemaTest` 兜底（逐表真实读写一遍）。

## 🧪 测试

```bash
mvn test
```

当前覆盖（70 个用例）：RRF 融合、检索指标与相关性判定、Token 桶限流、API Key 校验、
限流过滤器、SSE 流式对话、真实/估算 Token 计量、记忆注入、文档解析、Token 计数、内置工具，
以及 `MigrationSchemaTest`（启动 H2 + Flyway，验证 8 张表均可按实体定义读写）。

## 📊 监控指标

| 指标 | 说明 |
| --- | --- |
| `http_server_requests_seconds` | 请求耗时 |
| `token_usage_total_tokens` | Token 消耗 |
| `tool_calls_total` | 工具调用次数 |
| `tool_calls_failed` | 工具失败次数 |
| `jvm_memory_used_bytes` | JVM 内存使用 |

## 🐳 完整 Docker 部署

```bash
cp .env.example .env
docker compose up --build
```

将启动：应用、MySQL、Redis、Milvus、etcd、MinIO、Prometheus、Grafana。

## 📄 许可

本项目仅用于学习与个人使用。
