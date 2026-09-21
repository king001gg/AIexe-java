import { ref } from 'vue'

/** 全局 UI 开关。放模块作用域而不是 provide/inject —— 层级太浅，不值得。 */
export const settingsOpen = ref(false)
export const sidebarOpen = ref(true)
