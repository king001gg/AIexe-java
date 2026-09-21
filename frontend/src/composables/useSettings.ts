import { ref } from 'vue'
import { getApiKey, getBaseUrl, setApiKey, setBaseUrl } from '../api/client'

/**
 * 运行期设置。baseUrl 默认是相对路径 `/api`，走 Vite 代理，不触发 CORS。
 * 只有在「直接连后端、不经代理」时才需要改成绝对地址（并被后端 CORS 白名单放行）。
 */
export const baseUrl = ref(getBaseUrl())
export const apiKey = ref(getApiKey())

export function applySettings(next: { baseUrl: string; apiKey: string }) {
  setBaseUrl(next.baseUrl)
  setApiKey(next.apiKey)
  baseUrl.value = getBaseUrl()
  apiKey.value = getApiKey()
}

/** Vite 代理目标，与 vite.config.ts 保持一致 —— 仅用于在 UI 上提示 */
export const PROXY_TARGET = 'http://localhost:8080'
