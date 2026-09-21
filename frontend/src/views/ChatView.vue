<script setup lang="ts">
import { ArrowDown, Eraser, MessageSquarePlus, Sparkles } from 'lucide-vue-next'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import ChatMessage from '../components/ChatMessage.vue'
import Composer from '../components/Composer.vue'
import { chatApi } from '../api/endpoints'
import { streamChat } from '../api/client'
import { useToast } from '../composables/useToast'
import type { ChatTokenUsage, StreamEvent } from '../api/types'
import type { UiMessage } from '../types/ui'

const toast = useToast()
const LS_SID = 'airag.sessionId'

const sessionId = ref(localStorage.getItem(LS_SID) || newId())
const messages = ref<UiMessage[]>([])
const sending = ref(false)
const useTools = ref(true)
const tokens = ref<ChatTokenUsage | null>(null)
let abort: AbortController | null = null

function newId() {
  const id = crypto.randomUUID ? crypto.randomUUID() : 'sess-' + Math.random().toString(36).slice(2)
  localStorage.setItem(LS_SID, id)
  return id
}

/* ── 滚动：粘底，但用户手动上滚后就不再抢他的滚动位置 ── */
const scroller = ref<HTMLElement | null>(null)
const atBottom = ref(true)

function onScroll() {
  const el = scroller.value
  if (!el) return
  atBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 90
}
function toBottom(force = false) {
  const el = scroller.value
  if (!el) return
  if (force || atBottom.value) el.scrollTop = el.scrollHeight
}

watch(
  () => messages.value.length,
  () => nextTick(() => toBottom(true)),
)

/* ── 发送 ── */
async function send(text: string) {
  if (sending.value) return

  messages.value.push({ id: newId(), role: 'user', content: text })
  const ai: UiMessage = { id: newId(), role: 'assistant', content: '', streaming: true, tools: [], sources: [] }
  messages.value.push(ai)
  sending.value = true
  abort = new AbortController()

  const target = messages.value[messages.value.length - 1]

  try {
    await streamChat(
      { message: text, sessionId: sessionId.value, useTools: useTools.value },
      (ev: StreamEvent) => {
        switch (ev.name) {
          case 'sources':
            target.sources = Array.isArray(ev.data) ? ev.data : []
            break
          case 'tool':
            target.tools = [...(target.tools || []), ev.data]
            break
          case 'token':
            target.content += ev.data?.content ?? ''
            nextTick(() => toBottom())
            break
          case 'done':
            target.meta = ev.data
            break
          case 'error':
            target.error = ev.data?.message || '服务端返回未知错误'
            break
        }
      },
      abort.signal,
    )
  } catch (e) {
    if ((e as Error).name !== 'AbortError') {
      target.error = (e as Error).message || '请求失败'
    }
    if ((e as Error).name === 'AbortError') target.content ||= '（已停止）'
  } finally {
    target.streaming = false
    sending.value = false
    abort = null
    nextTick(() => toBottom())
    refreshTokens()
  }
}

function stop() {
  abort?.abort()
}

/** 重新生成：丢掉这条助手消息，把上一条用户消息重发一次 */
function retry(index: number) {
  const prev = messages.value[index - 1]
  if (!prev || prev.role !== 'user') return
  messages.value.splice(index)
  messages.value.pop()
  send(prev.content)
}

/* ── 会话操作 ── */
async function refreshTokens() {
  try {
    tokens.value = await chatApi.tokens(sessionId.value)
  } catch {
    /* 计量拿不到不影响主流程 */
  }
}

async function loadContext() {
  try {
    const ctx = await chatApi.context(sessionId.value)
    messages.value = ctx.messages
      .filter((m) => m.role === 'USER' || m.role === 'AI')
      .map((m) => ({
        id: newId(),
        role: m.role === 'USER' ? ('user' as const) : ('assistant' as const),
        content: m.content,
        // 历史消息不带 sources：后端没有把每轮的检索结果落库，
        // 只有 SSE 流里那一次。这是后端的数据模型限制，不是前端漏做。
        tools: (m.toolCalls || []).map((c) => ({
          toolName: c.name,
          toolInput: c.arguments,
          toolOutput: '(历史记录未保存工具结果)',
        })),
      }))
  } catch {
    messages.value = []
  }
}

function resetSession() {
  sessionId.value = newId()
  messages.value = []
  tokens.value = null
  toast.info('已切换到新会话')
}

async function clearContext() {
  try {
    await chatApi.clearContext(sessionId.value)
    messages.value = []
    tokens.value = null
    toast.ok('已清空该会话的服务端上下文')
  } catch (e) {
    toast.fromError(e, '清空上下文失败')
  }
}

onMounted(() => {
  loadContext()
  refreshTokens()
})

const empty = computed(() => messages.value.length === 0)

const SUGGESTIONS = [
  { t: '你好，你能做什么？', d: '纯对话，不触发工具' },
  { t: '帮我算一下 123456 × 654321', d: '触发 calculator 工具' },
  { t: '现在几点了？', d: '触发 datetime 工具' },
  { t: 'RAG 是什么？', d: '走检索，可观察召回情况' },
]
</script>

<template>
  <div class="flex min-h-0 flex-1 flex-col">
    <!-- 会话栏 -->
    <header class="flex shrink-0 items-center gap-2 border-b border-line px-4 py-2">
      <span class="text-[12px] text-faint">会话</span>
      <code class="truncate font-mono text-[11.5px] text-muted" :title="sessionId">{{ sessionId }}</code>
      <span v-if="tokens && tokens.totalTokens" class="text-[11px] text-faint">
        · 上下文 {{ tokens.totalTokens.toLocaleString('en-US') }} tokens
      </span>
      <span class="flex-1" />
      <button
        class="flex items-center gap-1.5 rounded-md border border-line px-2 py-1 text-[11.5px] text-muted transition hover:border-accent/50 hover:text-fg"
        @click="clearContext"
      >
        <Eraser class="size-3" /> 清空上下文
      </button>
      <button
        class="flex items-center gap-1.5 rounded-md border border-line px-2 py-1 text-[11.5px] text-muted transition hover:border-accent/50 hover:text-fg"
        @click="resetSession"
      >
        <MessageSquarePlus class="size-3" /> 新会话
      </button>
    </header>

    <!-- 消息区 -->
    <div ref="scroller" class="min-h-0 flex-1 overflow-y-auto" @scroll.passive="onScroll">
      <!-- 空状态：只在真的没有消息时出现，且建议项可点即发 -->
      <div v-if="empty" class="mx-auto flex h-full max-w-2xl flex-col items-center justify-center px-6 py-10">
        <Sparkles class="size-7 text-accent" />
        <h2 class="mt-3 text-[17px] font-semibold">AI RAG 问答 Agent</h2>
        <p class="mt-1.5 text-center text-[12.5px] leading-relaxed text-faint">
          多路召回（向量 + 关键词）+ RRF 融合，可调用工具，回答逐字流式返回。
          <br />试试下面这些——它们分别走不同的链路。
        </p>
        <div class="mt-6 grid w-full gap-2 sm:grid-cols-2">
          <button
            v-for="(s, i) in SUGGESTIONS"
            :key="s.t"
            class="animate-[fadeUp_.3s_ease-out_both] rounded-lg border border-line bg-surface px-3 py-2.5 text-left transition hover:border-accent/50 hover:bg-surface2"
            :style="{ animationDelay: `${i * 60}ms` }"
            @click="send(s.t)"
          >
            <div class="text-[12.5px]">{{ s.t }}</div>
            <div class="mt-0.5 text-[11px] text-faint">{{ s.d }}</div>
          </button>
        </div>
      </div>

      <div v-else class="mx-auto flex max-w-3xl flex-col gap-5 px-4 py-5">
        <template v-for="(m, i) in messages" :key="m.id">
          <ChatMessage :msg="m" :is-last="i === messages.length - 1" @retry="retry(i)" />
        </template>
      </div>
    </div>

    <!-- 回到底部：只在用户上滚离开时出现 -->
    <div class="relative">
      <button
        v-if="!atBottom"
        class="absolute -top-11 left-1/2 grid size-8 -translate-x-1/2 place-items-center rounded-full border border-line bg-surface2 text-muted shadow-lg transition hover:text-fg"
        aria-label="回到底部"
        @click="toBottom(true)"
      >
        <ArrowDown class="size-4" />
      </button>
    </div>

    <!-- 输入区 -->
    <div class="shrink-0 border-t border-line px-4 py-3">
      <div class="mx-auto max-w-3xl">
        <Composer :streaming="sending" v-model:use-tools="useTools" @send="send" @stop="stop" />
        <div class="mt-1.5 text-center text-[10.5px] text-faint">
          回答由大模型生成，检索结果可能为空——展开回答下方的「检索来源」可核对每一路的召回情况
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
@keyframes fadeUp {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
</style>
