<script setup lang="ts">
import { ArrowUp, Square, Wrench } from 'lucide-vue-next'
import { nextTick, ref, watch } from 'vue'

const props = defineProps<{ streaming: boolean }>()
const emit = defineEmits<{ send: [text: string]; stop: [] }>()

const text = ref('')
const useTools = defineModel<boolean>('useTools', { default: true })
const ta = ref<HTMLTextAreaElement | null>(null)

/** 自适应高度：先归零再取 scrollHeight，否则删字时不会缩回去 */
function resize() {
  const el = ta.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}
watch(text, () => nextTick(resize))

function submit() {
  const t = text.value.trim()
  if (!t || props.streaming) return
  emit('send', t)
  text.value = ''
  nextTick(resize)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    submit()
  }
}

defineExpose({ focus: () => ta.value?.focus() })
</script>

<template>
  <div
    class="rounded-xl border border-line bg-surface transition focus-within:border-accent/60"
    :class="{ 'opacity-80': streaming }"
  >
    <textarea
      ref="ta"
      v-model="text"
      rows="1"
      placeholder="问点什么…  Enter 发送，Shift+Enter 换行"
      class="block max-h-[200px] w-full resize-none bg-transparent px-3.5 pt-3 pb-1 text-[13.5px] leading-relaxed text-fg outline-none placeholder:text-faint"
      @keydown="onKeydown"
    />

    <!-- 控件放在输入壳内部；左组固定不缩，右组可缩 —— 窄容器下不会把发送键挤出去 -->
    <div class="flex items-center gap-2 px-2.5 pt-0.5 pb-2">
      <button
        class="flex shrink-0 items-center gap-1.5 rounded-md border px-2 py-1 text-[11.5px] transition"
        :class="
          useTools
            ? 'border-warn/40 bg-warn/10 text-warn'
            : 'border-line bg-surface2 text-faint hover:text-muted'
        "
        :title="useTools ? '本轮允许模型调用工具' : '本轮不调用工具'"
        @click="useTools = !useTools"
      >
        <Wrench class="size-3" />
        工具{{ useTools ? ' 开' : ' 关' }}
      </button>

      <span class="min-w-0 flex-1 truncate text-[11px] text-faint">
        {{ useTools ? 'calculator / search / datetime / weather / math' : '仅对话，不触发工具调用' }}
      </span>

      <button
        v-if="streaming"
        class="flex shrink-0 items-center gap-1.5 rounded-lg border border-danger/40 bg-danger/10 px-2.5 py-1.5 text-[12px] text-danger transition hover:bg-danger/20"
        @click="emit('stop')"
      >
        <Square class="size-3 fill-current" />
        停止
      </button>
      <button
        v-else
        class="grid size-8 shrink-0 place-items-center rounded-lg bg-accent text-white transition enabled:hover:brightness-110 disabled:cursor-not-allowed disabled:bg-surface3 disabled:text-faint"
        :disabled="!text.trim()"
        aria-label="发送"
        @click="submit"
      >
        <ArrowUp class="size-4" />
      </button>
    </div>
  </div>
</template>
