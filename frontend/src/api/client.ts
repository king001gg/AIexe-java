import type { ApiError, StreamEvent } from './types'

const LS_BASE = 'airag.baseUrl'

/**
 * API 根地址。
 *
 * 默认 `/api`（相对路径）——开发时由 Vite 代理转发到 :8080，
 * 浏览器视角全程同源，**不触发 CORS**。
 * 若把构建产物部署到别处、后端在另一个源，可在「设置」里改成绝对地址
 * （http://localhost:8080/api）——前提是该源在后端 CORS 白名单里。
 */
export function getBaseUrl(): string {
  const v = localStorage.getItem(LS_BASE)
  return v && v.trim() ? v.trim().replace(/\/+$/, '') : '/api'
}
export function setBaseUrl(v: string) {
  const t = v.trim().replace(/\/+$/, '')
  if (t && t !== '/api') localStorage.setItem(LS_BASE, t)
  else localStorage.removeItem(LS_BASE)
}

/** 可选的 API Key。security.enabled=true 的 profile（dev/test/容器）才需要。 */
const LS_KEY = 'airag.apiKey'
export function getApiKey(): string {
  return localStorage.getItem(LS_KEY) ?? ''
}
export function setApiKey(v: string) {
  if (v.trim()) localStorage.setItem(LS_KEY, v.trim())
  else localStorage.removeItem(LS_KEY)
}

/** 业务侧统一处理的错误类型 —— 已把后端 ApiError 体解成人话 */
export class ApiRequestError extends Error {
  /* 不用构造函数参数属性（constructor(readonly x: T)）：
   * tsconfig 开了 erasableSyntaxOnly，那种写法不是「可擦除语法」，vue-tsc 会直接报错。 */
  readonly status: number
  readonly body?: unknown

  constructor(message: string, status: number, body?: unknown) {
    super(message)
    this.name = 'ApiRequestError'
    this.status = status
    this.body = body
  }
}

function headers(extra?: Record<string, string>): Record<string, string> {
  const h: Record<string, string> = { ...extra }
  const key = getApiKey()
  // 后端 SecurityConfig 里 header 名固定为 X-API-Key（application.yml: security.header）
  if (key) h['X-API-Key'] = key
  return h
}

/** 把后端错误体或网络异常统一成 ApiRequestError */
async function toError(res: Response): Promise<ApiRequestError> {
  let body: unknown
  let text = ''
  try {
    text = await res.text()
    body = text ? JSON.parse(text) : undefined
  } catch {
    body = text
  }

  if (res.status === 401) {
    return new ApiRequestError(
      '未通过认证（401）。当前 profile 开启了 API Key 校验，请在「设置」里填入正确的 X-API-Key。',
      401,
      body,
    )
  }
  if (res.status === 403) {
    return new ApiRequestError('被拒绝（403）。可能是限流触发，或 API Key 无效。', 403, body)
  }

  const e = body as ApiError | undefined
  const msg = e?.message || `请求失败（HTTP ${res.status}）`
  return new ApiRequestError(e?.detail ? `${msg}：${e.detail}` : msg, res.status, body)
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response
  try {
    res = await fetch(getBaseUrl() + path, {
      ...init,
      headers: headers(init?.headers as Record<string, string>),
    })
  } catch (e) {
    throw new ApiRequestError(
      `连不上后端（${getBaseUrl()}）。确认应用已启动，或到「设置」里修正 API 地址。`,
      0,
      e,
    )
  }
  if (!res.ok) throw await toError(res)
  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export const http = {
  get: <T>(p: string) => request<T>(p),
  post: <T>(p: string, body?: unknown) =>
    request<T>(p, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
  del: <T>(p: string) => request<T>(p, { method: 'DELETE' }),

  /** multipart 上传：不要手动设 Content-Type，交给浏览器带 boundary */
  upload: <T>(p: string, form: FormData) => request<T>(p, { method: 'POST', body: form }),
}

/* ───────────────────────── SSE ───────────────────────── */

/**
 * 解析 `POST /chat/stream` 的 SSE 流并逐个事件回调。
 *
 * 为什么不用浏览器原生 EventSource：它只支持 GET，而本端点是 POST + JSON body。
 * 所以这里用 fetch + ReadableStream 手工切帧 —— 顺带也拿到了 AbortSignal 支持，
 * 「停止生成」才做得到。
 *
 * 帧格式由 Spring 的 SseEmitter 产生：`event:名字\n` + `data:载荷\n` + 空行。
 * 冒号后的空格是可选的，两种都要兼容。
 */
export async function streamChat(
  payload: unknown,
  onEvent: (ev: StreamEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  let res: Response
  try {
    res = await fetch(getBaseUrl() + '/chat/stream', {
      method: 'POST',
      headers: headers({ 'Content-Type': 'application/json', Accept: 'text/event-stream' }),
      body: JSON.stringify(payload),
      signal,
    })
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e
    throw new ApiRequestError(`连不上后端（${getBaseUrl()}）。`, 0, e)
  }

  if (!res.ok) throw await toError(res)

  // 后端在入参校验失败时可能回普通 JSON（虽已尽量走 SSE error 事件），兜一下
  const ct = res.headers.get('content-type') ?? ''
  if (!ct.includes('text/event-stream')) {
    const text = await res.text()
    throw new ApiRequestError(`期望 SSE 流，实际收到 ${ct || '未知类型'}：${text.slice(0, 300)}`, res.status)
  }
  if (!res.body) throw new ApiRequestError('响应没有 body，无法读取流。', res.status)

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''

  const dispatch = (frame: string) => {
    let name = 'message'
    const dataLines: string[] = []
    for (const line of frame.split('\n')) {
      if (line.startsWith(':')) continue // SSE 注释/心跳
      if (line.startsWith('event:')) name = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
    }
    if (!dataLines.length) return
    const raw = dataLines.join('\n')
    let data: unknown
    try {
      data = JSON.parse(raw)
    } catch {
      data = raw
    }
    onEvent({ name, data } as StreamEvent)
  }

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    // 归一化 CRLF，防止 \r\n\r\n 分帧漏判
    buf += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
    let i: number
    while ((i = buf.indexOf('\n\n')) >= 0) {
      const frame = buf.slice(0, i)
      buf = buf.slice(i + 2)
      if (frame.trim()) dispatch(frame)
    }
  }
  if (buf.trim()) dispatch(buf)
}
