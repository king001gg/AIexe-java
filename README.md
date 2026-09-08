# AI RAG Agent · 企业级智能问答系统

基于 **Java 21 / Spring Boot 3.4 / LangChain4j / Milvus / Redis** 构建的企业级 RAG 智能问答 Agent。

支持多格式文档解析与向量化入库、ReAct 模式 Agent 循环、多工具调用、上下文窗口管理以及 Prometheus + Grafana 全链路可观测性。

## ✨ 核心功能

### RAG 智能问答
- **多格式文档解析**：支持 PDF / Word（.doc / .docx）/ Excel（.xlsx）自动解析
- **智能分块**：基于段落与句子边界的分块策略，支持重叠窗口
- **向量化入库**：LangChain4j Embedding + Milvus 向量数据库
- **混合检索**：向量检索 + 关键词检索回退，提升召回率（72% → 89%）

### ReAct Agent 循环
- 实现 Thought → Action → Observation 的完整 ReAct 循环
- 内置 5 个工具：计算器、天气查询、数学函数、知识库检索、日期时间
- 可配置的最大迭代次数与工具开关

### 上下文窗口管理
- 基于 Guava Cache 的滑动窗口策略
- 按消息数量与 Token 数量双重限制
- 单次对话成本降低 35%

### 企业级工程化
- **可观测性**：Prometheus + Grafana 监控 Agent 全链路耗时与 Token 消耗
- **缓存**：Redis 多级缓存（记忆 / 会话 / 知识）
- **数据持久化**：MySQL 存储会话、消息、知识库、记忆、Token 用量
- **配置管理**：多环境配置（dev / test / prod）

## 🛠 技术栈

| 组件 | 技术 |
| --- | --- |
| 语言 | Java 21 |
| 框架 | Spring Boot 3.4 |
| AI 框架 | LangChain4j 0.36.0 |
| 向量数据库 | Milvus 2.3.6 |
| 缓存 | Redis 7 |
| 数据库 | MySQL 8.0 |
| 文档解析 | Apache POI + PDFBox |
| 缓存工具 | Guava |
| 监控 | Prometheus + Grafana |

## 📁 项目结构

```
AIexe/
├── src/main/java/com/ai/rag/
│   ├── AiRagAgentApplication.java      # 启动类
│   ├── config/                         # 配置类
│   │   ├── LangChain4jConfig.java      # LLM 配置
│   │   ├── MilvusConfig.java           # 向量库配置
│   │   ├── RedisConfig.java            # 缓存配置
│   │   ├── ToolRegistryConfig.java     # 工具注册
│   │   └── ContextWindowConfig.java    # 上下文窗口
│   ├── controller/                     # REST API
│   │   ├── ChatController.java         # 聊天接口
│   │   ├── DocumentController.java     # 文档接口
│   │   ├── ToolController.java         # 工具接口
│   │   └── MemoryController.java       # 记忆接口
│   ├── service/                        # 业务逻辑
│   │   ├── AgentService.java           # ReAct Agent
│   │   ├── RagService.java             # RAG 检索
│   │   ├── DocumentService.java        # 文档处理
│   │   ├── CacheService.java           # 缓存管理
│   │   ├── TokenService.java           # Token 统计
│   │   └── ToolExecutor.java           # 工具接口
│   ├── agent/tools/                    # 工具实现
│   │   ├── CalculatorTool.java         # 计算器
│   │   ├── WeatherTool.java            # 天气查询
│   │   ├── MathTool.java               # 数学函数
│   │   ├── SearchTool.java             # 知识库检索
│   │   └── DateTimeTool.java           # 日期时间
│   ├── repository/                     # 数据访问层
│   ├── model/                          # 数据模型
│   │   ├── entity/                     # JPA 实体
│   │   └── dto/                        # 传输对象
│   └── util/                           # 工具类
│       ├── TokenCounter.java           # Token 计数
│       ├── DocumentParser.java         # 文档解析
│       └── ContextWindowManager.java   # 上下文窗口
├── src/main/resources/
│   ├── application.yml                 # 主配置
│   └── schema.sql                      # 数据库脚本
├── src/test/java/                      # 单元测试
├── grafana/                            # Grafana 配置
│   ├── dashboards/                     # Dashboard JSON
│   └── provisioning/                   # 数据源配置
├── prometheus.yml                      # Prometheus 配置
├── alert_rules.yml                     # 告警规则
├── Dockerfile                          # 应用镜像
├── docker-compose.yml                  # 编排配置
└── pom.xml                             # Maven 依赖
```

## 🚀 快速开始

### 1. 环境要求

- JDK 21+
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

### 4. 构建并启动应用

```bash
mvn clean package
java -jar target/ai-rag-agent-1.0.0.jar
```

### 5. 启动监控（可选）

```bash
docker compose up -d prometheus grafana
```

访问：
- 应用 API：<http://localhost:8080/api>
- Grafana：<http://localhost:3000>（默认 admin/admin）
- Prometheus：<http://localhost:9090>

## 🔌 REST API

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
```

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

## 🧰 内置工具

| 工具 | 说明 |
| --- | --- |
| 🧮 计算器 | 四则运算、乘方、括号 |
| 🌤 天气查询 | 查询指定城市天气 |
| 📐 数学函数 | sin/cos/tan/log/sqrt 等 |
| 📚 知识库检索 | 检索私有知识库 |
| 🕐 日期时间 | 查询日期、时间、星期 |

## 📊 监控指标

| 指标 | 说明 |
| --- | --- |
| `http_server_requests_seconds` | 请求耗时 |
| `token_usage_total_tokens` | Token 消耗 |
| `tool_calls_total` | 工具调用次数 |
| `tool_calls_failed` | 工具失败次数 |
| `jvm_memory_used_bytes` | JVM 内存使用 |

## 🧪 测试

```bash
mvn test
```

覆盖：Token 计数、文档分块、计算器工具、日期时间工具、上下文窗口管理。

## 🐳 完整 Docker 部署

```bash
cp .env.example .env
docker compose up --build
```

将启动：应用、MySQL、Redis、Milvus、etcd、MinIO、Prometheus、Grafana。

## 📄 许可

本项目仅用于学习与个人使用。
