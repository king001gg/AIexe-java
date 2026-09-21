# AI RAG Agent 调试台

后端（`../` 的 Spring Boot 应用）是**纯 API**，仓库里原本没有任何人机界面 ——
`ApiIndexController` 的注释写得很明白：根路径刻意只返回 JSON 而不是 HTML，
因为「一个 HTML 落地页会暗示它有人机界面，反而误导」。

这个前端是给调试用的：把 17 个端点和几处**容易静默退化**的地方摆到台面上。

## 跑起来

后端先起（见仓库根目录的说明，只有 `local` profile 开箱可用）：

```bash
java -jar target/ai-rag-agent-1.0.0.jar --spring.profiles.active=local
```

然后：

```bash
npm install
npm run dev        # http://localhost:3000
```

`npm run build` 会先跑 `vue-tsc -b` 做类型检查再打包。

## 为什么开发时不需要配 CORS

`vite.config.ts` 把 `/api` 代理到 `http://localhost:8080`，浏览器视角全程同源，
**CORS 根本不会触发**。3000 端口同时也被写进了后端的 `LoggingConfig` 白名单，
但那只是兜底 —— 真正让跨域问题消失的是代理。

代理里有一段专门处理 SSE 的逻辑：删掉 `content-encoding`，否则代理层会把 token
攒成一坨再吐出来，流式打字效果直接消失。

若要部署到别处、后端在另一个源，到「设置」里把 API 地址改成绝对路径
（如 `http://localhost:8080/api`），前提是该源在后端 CORS 白名单里。

## 六个模块

| 模块 | 干什么 |
|---|---|
| 对话 | SSE 流式问答，工具调用轨迹，引用来源面板 |
| 知识库 | 上传、分块浏览、**检索诊断**（唯一能看到分路排名的入口） |
| 工具 | 已注册工具列表与逐个试跑 |
| 记忆 | 长期记忆的写入与读取 |
| 评测 | 跑检索评测集 |
| 状态 | 健康检查、运行时配置、后端自述的接口清单 |

## 设计上几个刻意的选择

- **非对称消息渲染**：用户是紧凑右对齐气泡，assistant 全宽无边框纯 markdown。
  两侧对称的聊天界面读长回答很累。
- **分数条按本轮最大值归一化**，原始 RRF 值单独以文本标出并写明是 `RRF`。
  融合分的绝对值没有可比性，只有相对序有意义 —— 拿它乘 100 当百分比是错的。
- **流式 markdown 用 `requestAnimationFrame` 批处理**，且流式期间不做语法高亮
  （未闭合的代码块本来也高亮不对），等 `done` 再统一跑一次。
- **工具轨迹流式中自动展开、结束自动收起**；用户手动点过之后就不再自动覆盖。
- **引用面板头部常驻分路汇总**（`双路 N / 向量 N / 关键词 N`），折叠时也看得见。

## 三个诚实的局限

1. **来源不落库**。后端没有按消息持久化检索结果，所以刷新后从
   `/chat/context` 恢复的历史消息没有引用 —— 分路泳道只在实时 SSE 那一轮出现。
2. **没有浏览器内测试**。构建与类型检查覆盖了编译期错误，但运行时行为靠手点。
3. **向量路可能是死的**。用 DeepSeek 做 chat 时它不提供 `/embeddings`，而
   `embeddingModel` 与 `chatModel` 共用 baseUrl/apiKey，于是上传照样返回
   `success:true`、健康检查照样 UP，但一条向量都没写进去，向量路恒为 0。
   引用面板检测到这种情况会**主动弹告警**，而不是安静地只显示关键词命中。

## 两个会绊住人的约束

- **`erasableSyntaxOnly`**：`tsconfig` 开了这个标志，构造函数参数属性
  （`constructor(readonly x: T)`）不是可擦除语法，`vue-tsc` 会直接报错。
- **`<script setup>` 不能有 `export`**：共享类型放在 `src/types/ui.ts`，
  不要试图从组件里导出。
