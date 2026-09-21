import type { StreamDoneEvent } from '../api/types'

/*
 * 组件的对外类型集中在这里。
 * 不能写在各自 SFC 的 <script setup> 里 —— 那个块不允许出现 export 语句，
 * 在别处 `import type { X } from './X.vue'` 会编译失败。
 */

/**
 * 归一化后的检索命中。两种来源形状不同：
 *  - SSE event:sources        → score 是**字符串**，且**没有分路排名**
 *  - /documents/search/detailed → score 是 number，且有 vectorRank / keywordRank
 * 所以排名做成可选：有就画分路泳道，没有就只显示汇总。
 */
export interface SourceItem {
  chunkId?: string | null
  documentId?: string | number | null
  title?: string | null
  score?: string | number | null
  source?: string | null
  preview?: string | null
  vectorRank?: number
  keywordRank?: number
}

export interface TraceItem {
  toolName: string
  toolInput: string
  toolOutput: string
  /** 仅 /chat/message（非流式）会给 status；SSE 不给 */
  status?: string
}

export interface UiMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  streaming?: boolean
  error?: string
  sources?: SourceItem[]
  tools?: TraceItem[]
  meta?: StreamDoneEvent | null
}
