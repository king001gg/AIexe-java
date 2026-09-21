<script setup lang="ts">
import { AlertTriangle, Check, Copy, RotateCcw } from 'lucide-vue-next'
import { ref } from 'vue'
import MarkdownBlock from './MarkdownBlock.vue'
import SourcePanel from './SourcePanel.vue'
import ToolTrace from './ToolTrace.vue'
import type { UiMessage } from '../types/ui'

defineProps<{ msg: UiMessage; isLast?: boolean }>()
const emit = defineEmits<{ retry: [] }>()

const copied = ref(false)
function copy(text: string) {
  navigator.clipboard?.writeText(text).then(
    () => {
      copied.value = true
      setTimeout(() => (copied.value = false), 1400)
    },
    () => {},
  )
}

const fmt = (n?: number) => (n == null ? '—' : n.toLocaleString('en-US'))
</script>

<template>
  <!-- 用户：紧凑右对齐气泡 -->
  <div v-if="msg.role === 'user'" class="flex justify-end">
    <div class="max-w-[85%] rounded-2xl rounded-br-md bg-accent px-3.5 py-2 text-[13.5px] whitespace-pre-wrap break-words text-white">
      {{ msg.content }}
    </div>
  </div>

  <!-- 助手：全宽、不加气泡。
       气泡会把宽度截断，markdown 的表格/代码/多级列表在窄容器里排版会很难看，
       多家实现都专门为此去掉过助手气泡。 -->
  <div v-else class="group">
    <ToolTrace v-if="msg.tools?.length" :items="msg.tools" :streaming="msg.streaming" />

    <MarkdownBlock v-if="msg.content" :text="msg.content" :streaming="msg.streaming" />
    <!-- 还没有任何 token：给个占位，否则用户不知道请求发出去了 -->
    <div v-else-if="msg.streaming" class="flex items-center gap-2 text-[13px] text-faint">
      <span class="flex gap-1">
        <i class="size-1.5 animate-bounce rounded-full bg-faint [animation-delay:0ms]" />
        <i class="size-1.5 animate-bounce rounded-full bg-faint [animation-delay:150ms]" />
        <i class="size-1.5 animate-bounce rounded-full bg-faint [animation-delay:300ms]" />
      </span>
      正在检索与生成…
    </div>

    <div v-if="msg.error" class="mt-2 flex items-start gap-2 rounded-lg border border-danger/40 bg-danger/8 px-3 py-2 text-[12.5px] text-danger">
      <AlertTriangle class="mt-0.5 size-3.5 shrink-0" />
      <span class="whitespace-pre-wrap break-words">{{ msg.error }}</span>
      <button
        v-if="isLast"
        class="ml-auto shrink-0 rounded border border-danger/40 px-2 py-0.5 text-[11px] transition hover:bg-danger/15"
        @click="emit('retry')"
      >
        重试
      </button>
    </div>

    <SourcePanel v-if="msg.sources?.length" :items="msg.sources" :open="false" />

    <!-- 计量条：只在答完后显示 -->
    <div v-if="!msg.streaming && (msg.meta || msg.content)" class="mt-1.5 flex items-center gap-3 text-[11px] text-faint">
      <span v-if="msg.meta">
        tokens {{ fmt(msg.meta.inputTokens) }} → {{ fmt(msg.meta.outputTokens) }}
        <template v-if="msg.meta.tokensEstimated"> (估算)</template>
      </span>
      <span v-if="msg.meta?.cost != null">${{ Number(msg.meta.cost).toFixed(6) }}</span>

      <span class="ml-auto flex items-center gap-1 opacity-0 transition group-hover:opacity-100 focus-within:opacity-100">
        <button
          class="rounded p-1 transition hover:bg-surface3 hover:text-fg"
          aria-label="复制回答"
          :title="copied ? '已复制' : '复制回答'"
          @click="copy(msg.content)"
        >
          <Check v-if="copied" class="size-3.5 text-ok" />
          <Copy v-else class="size-3.5" />
        </button>
        <button
          class="rounded p-1 transition hover:bg-surface3 hover:text-fg"
          aria-label="重新生成"
          title="重新生成"
          @click="emit('retry')"
        >
          <RotateCcw class="size-3.5" />
        </button>
      </span>
    </div>
  </div>
</template>
