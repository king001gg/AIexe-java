import { onMounted, onUnmounted, ref } from 'vue'
import { healthApi } from '../api/endpoints'
import type { HealthStatus } from '../api/types'

const status = ref<HealthStatus | null>(null)
const error = ref<string>('')
const checking = ref(false)
let timer: number | undefined
let mounted = 0

/**
 * 全局单例：侧边栏底部那个状态灯和「运行状态」页共用同一个轮询。
 * 引用计数保证多个组件同时用也不会起多个定时器。
 */
export function useHealth(intervalMs = 15000) {
  async function check() {
    checking.value = true
    try {
      status.value = await healthApi.get()
      error.value = ''
    } catch (e) {
      status.value = null
      error.value = (e as Error).message || '健康检查失败'
    } finally {
      checking.value = false
    }
  }

  onMounted(() => {
    if (mounted++ === 0) {
      check()
      timer = window.setInterval(check, intervalMs)
    }
  })

  onUnmounted(() => {
    if (--mounted === 0 && timer) {
      clearInterval(timer)
      timer = undefined
    }
  })

  return { status, error, checking, check }
}
