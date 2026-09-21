<script setup lang="ts">
import { ChevronDown, Wrench } from 'lucide-vue-next'
import { ref, watch } from 'vue'
import type { TraceItem } from '../types/ui'

const props = withDefaults(defineProps<{ items: TraceItem[]; streaming?: boolean }>(), {
  streaming: false,
})

/*
 * 各家（Vercel AI Elements 的 Reasoning、Flutter agent_kit 的 ThinkingBubble）
 * 收敛到同一个行为：**流式期间自动展开，结束后自动收起**。
 * 用户能看着它干活，但答案写完后台面要让给答案本身。
 * 一旦用户手动点过，就不再自动覆盖他的选择。
 */
// 显式标注 boolean：withDefaults 把 streaming 收窄成了字面量 false，
// 不标注的话 ref 会被推断成 Ref<false>，后面赋 true 直接编译失败
const expanded = ref<boolean>(props.streaming)
// 显式标 boolean：不标的话推断成字面量 false，后面赋 true 编译不过
let userTouched: boolean = false

function toggle() {
  userTouched = true
  expanded.value = !expanded.value
}

watch(
  () => props.streaming,
  (s) => {
    if (!userTouched) expanded.value = s
  },
)
watch(
  () => props.items.length,
  () => {
    if (props.streaming && !userTouched) expanded.value = true
  },
)
</script>

<template>
  <div class="mb-2 overflow-hidden rounded-lg border border-line bg-surface">
    <button
      class="flex w-full items-center gap-2 px-3 py-2 text-left transition hover:bg-surface2"
      :aria-expanded="expanded"
      @click="toggle"
    >
      <ChevronDown class="size-3.5 shrink-0 text-faint transition" :class="{ '-rotate-90': !expanded }" />
      <Wrench class="size-3.5 shrink-0 text-warn" />
      <span class="text-[12px] font-medium">
        {{ streaming ? '正在执行工具…' : '工具调用过程' }}
      </span>
      <span class="text-[12px] text-faint">{{ items.length }} 次</span>
      <span class="ml-auto flex gap-1">
        <span
          v-for="(t, i) in items.slice(0, 6)"
          :key="i"
          class="rounded bg-surface3 px-1.5 py-px font-mono text-[10px] text-muted"
        >
          {{ t.toolName }}
        </span>
      </span>
    </button>

    <div v-if="expanded" class="border-t border-line">
      <div v-for="(t, i) in items" :key="i" class="border-b border-line-soft px-3 py-2 last:border-b-0">
        <div class="flex items-center gap-2">
          <span class="grid size-4 place-items-center rounded bg-surface3 text-[10px] text-muted">{{ i + 1 }}</span>
          <code class="font-mono text-[11.5px] text-warn">{{ t.toolName }}</code>
          <span
            v-if="t.status"
            class="rounded border px-1.5 py-px text-[10px]"
            :class="
              String(t.status).toUpperCase() === 'SUCCESS'
                ? 'border-ok/40 bg-ok/10 text-ok'
                : 'border-danger/40 bg-danger/10 text-danger'
            "
          >
            {{ t.status }}
          </span>
        </div>
        <div class="mt-1.5 grid gap-1 pl-6 text-[11.5px]">
          <div class="flex gap-2">
            <span class="w-10 shrink-0 text-faint">入参</span>
            <code class="min-w-0 flex-1 break-all font-mono text-muted">{{ t.toolInput || '(空)' }}</code>
          </div>
          <div class="flex gap-2">
            <span class="w-10 shrink-0 text-faint">结果</span>
            <code class="min-w-0 flex-1 break-all font-mono text-fg">{{ t.toolOutput || '(空)' }}</code>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
