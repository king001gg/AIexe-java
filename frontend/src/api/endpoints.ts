import { http } from './client'
import type {
  ChatContext,
  ChatResponse,
  ChatTokenUsage,
  DetailedSearchResult,
  DocumentSummary,
  EvaluationReport,
  HealthStatus,
  KnowledgeBaseSummary,
  Memory,
  MemoryGetResult,
  MemorySaveResult,
  ToolExecuteResult,
  ToolInfo,
} from './types'

const q = (params: Record<string, string | number | undefined | null>) => {
  const sp = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') sp.set(k, String(v))
  }
  const s = sp.toString()
  return s ? `?${s}` : ''
}

/* ─────────────── 对话 ─────────────── */
export const chatApi = {
  /** 非流式一次性问答 */
  send: (body: { message: string; sessionId?: string; useTools?: boolean; enabledTools?: string[] }) =>
    http.post<ChatResponse>('/chat/message', body),

  context: (sessionId: string) => http.get<ChatContext>(`/chat/context/${encodeURIComponent(sessionId)}`),

  clearContext: (sessionId: string) =>
    http.del<{ sessionId: string; cleared: boolean; message: string }>(
      `/chat/context/${encodeURIComponent(sessionId)}`,
    ),

  tokens: (sessionId: string) => http.get<ChatTokenUsage>(`/chat/tokens/${encodeURIComponent(sessionId)}`),
}

/* ─────────────── 知识库 / 文档 ─────────────── */
export const docsApi = {
  list: () => http.get<KnowledgeBaseSummary[]>('/documents/knowledge-bases'),

  get: (id: number) => http.get<KnowledgeBaseSummary>(`/documents/knowledge-bases/${id}`),

  stats: (id: number) => http.get<Record<string, unknown>>(`/documents/knowledge-bases/${id}/stats`),

  remove: (id: number) =>
    http.del<{ success: boolean; message: string }>(`/documents/knowledge-bases/${id}`),

  chunks: (id: number) => http.get<DocumentSummary[]>(`/documents/knowledge-bases/${id}/documents`),

  /**
   * 上传。file 必填、knowledgeBaseName 必填（后端 @NotBlank），
   * 其余走后端默认：chunkSize=1000 / chunkOverlap=200。
   */
  upload: (opts: {
    file: File
    knowledgeBaseName: string
    description?: string
    createdBy?: string
    chunkSize?: number
    chunkOverlap?: number
  }) => {
    const fd = new FormData()
    fd.append('file', opts.file)
    fd.append('knowledgeBaseName', opts.knowledgeBaseName)
    if (opts.description) fd.append('description', opts.description)
    if (opts.createdBy) fd.append('createdBy', opts.createdBy)
    if (opts.chunkSize != null) fd.append('chunkSize', String(opts.chunkSize))
    if (opts.chunkOverlap != null) fd.append('chunkOverlap', String(opts.chunkOverlap))
    return http.upload<import('./types').UploadResult>('/documents/upload', fd)
  },

  search: (query: string, knowledgeBaseId?: number, topK = 5) =>
    http.get<DocumentSummary[]>(`/documents/search${q({ query, knowledgeBaseId, topK })}`),

  /** 带多路召回排名与 RRF 融合分 —— 「检索诊断」页用它 */
  searchDetailed: (query: string, knowledgeBaseId?: number, topK = 5) =>
    http.get<DetailedSearchResult>(`/documents/search/detailed${q({ query, knowledgeBaseId, topK })}`),
}

/* ─────────────── 工具 ─────────────── */
export const toolsApi = {
  list: () => http.get<string[]>('/tools'),
  info: (name: string) => http.get<ToolInfo>(`/tools/${encodeURIComponent(name)}`),
  /** arguments 字段**必须存在**（可为 ""），否则后端 400 —— 见缺陷 D9 */
  execute: (name: string, args: string) =>
    http.post<ToolExecuteResult>(`/tools/${encodeURIComponent(name)}/execute`, { arguments: args }),
}

/* ─────────────── 长期记忆 ─────────────── */
export const memoryApi = {
  save: (body: { sessionId: string; key: string; value: string }) =>
    http.post<MemorySaveResult>('/memory/save', body),

  get: (sessionId: string, key: string) =>
    http.get<MemoryGetResult>(`/memory/${encodeURIComponent(sessionId)}/${encodeURIComponent(key)}`),

  list: (sessionId: string) => http.get<Memory[]>(`/memory/${encodeURIComponent(sessionId)}`),

  remove: (sessionId: string, key: string) =>
    http.del<{ success: boolean; message: string }>(
      `/memory/${encodeURIComponent(sessionId)}/${encodeURIComponent(key)}`,
    ),

  clear: (sessionId: string) =>
    http.del<{ success: boolean; message: string }>(`/memory/${encodeURIComponent(sessionId)}`),
}

/* ─────────────── 检索评测 ─────────────── */
export const evalApi = {
  run: (body: { dataset?: string; knowledgeBaseId?: number; topK?: number }) =>
    http.post<EvaluationReport>('/evaluation/retrieval', body),

  dataset: () => http.get<unknown>('/evaluation/dataset'),

  info: () => http.get<Record<string, unknown>>('/evaluation/info'),
}

/* ─────────────── 健康 ─────────────── */
/**
 * 注意上下文根路径是 /api，actuator 也随之挂在 /api/actuator 下。
 * 这个端点在 security 开启时属于 permit-all，**不需要 API Key**
 * （见 application.yml: security.permit-all）。
 */
export const healthApi = {
  get: () => http.get<HealthStatus>('/actuator/health'),
}
