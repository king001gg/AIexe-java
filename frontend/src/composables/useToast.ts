import { ref } from 'vue'

export type ToastKind = 'ok' | 'err' | 'info' | 'warn'

export interface Toast {
  id: number
  kind: ToastKind
  text: string
  /** 第二行小字，用来塞后端返回的 detail */
  detail?: string
}

const items = ref<Toast[]>([])
let seq = 0

function push(kind: ToastKind, text: string, detail?: string, ms = 4200) {
  const id = ++seq
  items.value.push({ id, kind, text, detail })
  if (ms > 0) setTimeout(() => dismiss(id), ms)
  return id
}

function dismiss(id: number) {
  const i = items.value.findIndex((t) => t.id === id)
  if (i >= 0) items.value.splice(i, 1)
}

export function useToast() {
  return {
    items,
    dismiss,
    ok: (t: string, d?: string) => push('ok', t, d),
    err: (t: string, d?: string) => push('err', t, d, 7000),
    info: (t: string, d?: string) => push('info', t, d),
    warn: (t: string, d?: string) => push('warn', t, d, 6000),
    /** 统一处理捕获到的异常：把 ApiRequestError 的 message/detail 拆开显示 */
    fromError: (e: unknown, fallback = '操作失败') => {
      const err = e as { message?: string; body?: unknown }
      const detail =
        err?.body && typeof err.body === 'object'
          ? ((err.body as Record<string, unknown>).detail as string | undefined)
          : undefined
      push('err', err?.message || fallback, detail, 8000)
    },
  }
}
