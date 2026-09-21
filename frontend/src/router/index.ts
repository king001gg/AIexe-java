import { createRouter, createWebHashHistory } from 'vue-router'

/**
 * 用 hash 模式而不是 history 模式。
 *
 * 原因：构建产物是纯静态文件，可能被丢到任意静态服务器、甚至直接用 file:// 打开
 * （或用 python -m http.server 起）。history 模式需要服务端把未知路径 fallback 到
 * index.html，否则刷新 /knowledge 就 404。hash 模式没有这个前提条件。
 */
export const routes = [
  {
    path: '/chat',
    name: 'chat',
    component: () => import('../views/ChatView.vue'),
    meta: { title: '对话', icon: 'chat', desc: 'RAG 问答主链路' },
  },
  {
    path: '/knowledge',
    name: 'knowledge',
    component: () => import('../views/KnowledgeView.vue'),
    meta: { title: '知识库', icon: 'db', desc: '上传 / 分块 / 检索诊断' },
  },
  {
    path: '/tools',
    name: 'tools',
    component: () => import('../views/ToolsView.vue'),
    meta: { title: '工具', icon: 'wrench', desc: '执行已注册工具' },
  },
  {
    path: '/memory',
    name: 'memory',
    component: () => import('../views/MemoryView.vue'),
    meta: { title: '长期记忆', icon: 'brain', desc: '跨会话键值记忆' },
  },
  {
    path: '/evaluation',
    name: 'evaluation',
    component: () => import('../views/EvaluationView.vue'),
    meta: { title: '检索评测', icon: 'chart', desc: 'Recall / Precision / MRR' },
  },
  {
    path: '/status',
    name: 'status',
    component: () => import('../views/StatusView.vue'),
    meta: { title: '运行状态', icon: 'pulse', desc: '健康检查与配置' },
  },
  { path: '/:pathMatch(.*)*', redirect: '/chat' },
]

export default createRouter({
  history: createWebHashHistory(),
  routes,
})
