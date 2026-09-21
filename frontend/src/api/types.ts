/**
 * 与后端 DTO 一一对应的类型声明。
 *
 * 全部照抄自 java 侧实际字段（record / @Data 类），没有臆造字段。
 * 标注「后端 Map 拼装」的接口没有 DTO，字段以实际 controller 里 put 的 key 为准。
 */

/* ─────────────── 通用错误体 ───────────────
 * 由 GlobalExceptionHandler / JsonAuthenticationEntryPoint 产出。
 * 注意 401（缺 API Key）与 400（参数校验）都是这个形状。 */
export interface ApiError {
  timestamp: string
  status: number
  message: string
  detail?: string
  path: string
}

/* ─────────────── 对话 ─────────────── */

export interface ChatRequest {
  message: string
  sessionId?: string
  conversationId?: string
  nickname?: string
  personality?: string
  useTools?: boolean
  enabledTools?: string[]
  context?: Record<string, unknown>
}

export interface ToolCall {
  toolName: string
  toolInput: string
  toolOutput: string
  status: string
}

export interface ChatResponse {
  conversationId: string
  sessionId: string
  response: string
  toolCalls: ToolCall[]
  inputTokens: number
  outputTokens: number
  totalTokens: number
  cost: number
  conversationTitle: string
  timestamp: string
}

/** GET /chat/context/{sessionId} 里的一项 */
export interface ContextEntry {
  role: 'USER' | 'AI' | 'SYSTEM' | 'TOOL_EXECUTION_RESULT' | string
  content: string
  /** 仅当该条 AI 消息发起了工具调用时存在（后端 toHistoryEntry 单独透出） */
  toolCalls?: { name: string; arguments: string }[]
}

export interface ChatContext {
  sessionId: string
  messageCount: number
  messages: ContextEntry[]
}

export interface ChatTokenUsage {
  sessionId: string
  inputTokens: number
  outputTokens: number
  totalTokens: number
}

/* ─────────────── SSE 流事件载荷 ─────────────── */

/** event:sources —— 注意 score 是**字符串**（从 metadata 取出的 rrfScore） */
export interface SourceHit {
  chunkId: string
  documentId: string
  /** RRF 融合分，后端已格式化为 %.6f 的字符串，不是余弦相似度 */
  score: string
  source: string
  /** 1-based；0 = 该路未召回。可选：老版本后端不发这两个字段 */
  vectorRank?: number
  keywordRank?: number
  preview: string
}

/** event:tool */
export interface StreamToolEvent {
  toolName: string
  toolInput: string
  toolOutput: string
}

/** event:token */
export interface StreamTokenEvent {
  content: string
}

/** event:done */
export interface StreamDoneEvent {
  conversationId: string
  sessionId: string
  response: string
  conversationTitle: string
  inputTokens: number
  outputTokens: number
  totalTokens: number
  /** 模型未回传用量时为 true，表示 token 数是估算的 */
  tokensEstimated: boolean
  cost: number
}

/** event:error */
export interface StreamErrorEvent {
  message: string
}

export type StreamEvent =
  | { name: 'sources'; data: SourceHit[] }
  | { name: 'tool'; data: StreamToolEvent }
  | { name: 'token'; data: StreamTokenEvent }
  | { name: 'done'; data: StreamDoneEvent }
  | { name: 'error'; data: StreamErrorEvent }

/* ─────────────── 知识库 / 文档 ─────────────── */

export interface KnowledgeBaseSummary {
  id: number
  name: string
  description: string | null
  fileName: string | null
  fileSize: number | null
  docCount: number | null
  totalTokens: number | null
  createdBy: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface DocumentSummary {
  id: number
  knowledgeBaseId: number | null
  title: string | null
  content: string
  chunkId: string | null
  chunkIndex: number | null
  tokens: number | null
  vectorId: string | null
  createdAt: string | null
}

/** POST /documents/upload（multipart） */
export interface UploadResult {
  success: boolean
  knowledgeBaseId?: number
  documentCount?: number
  totalTokens?: number
  message?: string
}

/**
 * GET /documents/search/detailed 的一条命中。
 *
 * 关键：score 是 **RRF 融合分**（越高越相关），不是余弦相似度；
 * vectorRank / keywordRank 是各路召回的 1-based 排名，**0 表示该路没召回**。
 * 这是本项目唯一能看见「向量路是否真的在工作」的地方 —— 见 SourceRank 组件的注释。
 */
export interface DetailedSearchHit {
  documentId: number
  chunkId: string | null
  title: string | null
  score: number
  vectorRank: number
  keywordRank: number
  source: 'hybrid' | 'vector' | 'keyword' | string
}

export interface DetailedSearchResult {
  query: string
  knowledgeBaseId: number | null
  count: number
  hits: DetailedSearchHit[]
}

/* ─────────────── 工具 ─────────────── */

/** POST /tools/{name}/execute —— 成功体 */
export interface ToolExecuteSuccess {
  success: true
  toolName: string
  result: string
}
/** 失败体：400 形状问题（无 toolName）或 500 执行失败（有 toolName） */
export interface ToolExecuteFailure {
  success: false
  toolName?: string
  message: string
}
export type ToolExecuteResult = ToolExecuteSuccess | ToolExecuteFailure

export interface ToolInfo {
  name: string
  available: boolean
}

/* ─────────────── 长期记忆 ─────────────── */

export interface Memory {
  id: number
  sessionId: string
  key: string
  value: string
  createdAt: string
  updatedAt: string
}

export interface MemorySaveResult {
  success: boolean
  memoryId?: number
  message?: string
}

export interface MemoryGetResult {
  sessionId: string
  key: string
  value: string
  /** true 表示命中 Redis 缓存 */
  fromCache: boolean
}

/* ─────────────── 检索评测 ─────────────── */

export interface EvalCaseResult {
  caseId: string
  question: string
  retrievedCount: number
  expectedCount: number
  satisfiedCount: number
  firstRelevantRank: number
  recall: number
  precision: number
  reciprocalRank: number
  hit: boolean
  missing: string[]
}

export interface EvalAggregate {
  caseCount: number
  recallAtK: number
  precisionAtK: number
  mrr: number
  hitRate: number
}

export interface EvaluationReport {
  dataset: string
  topK: number
  knowledgeBaseId: number | null
  caseCount: number
  aggregate: EvalAggregate
  cases: EvalCaseResult[]
  notes: string[]
  startedAt: string
}

/* ─────────────── 健康 ─────────────── */

export interface HealthStatus {
  status: 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN' | string
  components?: Record<string, { status: string; details?: Record<string, unknown> }>
}
