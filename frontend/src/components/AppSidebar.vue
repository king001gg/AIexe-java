<script setup lang="ts">
import {
  Activity,
  BarChart3,
  Brain,
  Database,
  MessageSquare,
  Settings,
  Wrench,
} from 'lucide-vue-next'
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { routes } from '../router'
import { useHealth } from '../composables/useHealth'
import { settingsOpen } from '../composables/useUi'

const route = useRoute()
const { status, error, checking, check } = useHealth()

const icons: Record<string, unknown> = {
  chat: MessageSquare,
  db: Database,
  wrench: Wrench,
  brain: Brain,
  chart: BarChart3,
  pulse: Activity,
}

const nav = computed(() => routes.filter((r) => r.meta?.title))

const health = computed(() => {
  if (error.value) return { cls: 'bg-danger', text: '不可达', title: error.value }
  const s = status.value?.status
  if (!s) return { cls: 'bg-faint', text: checking.value ? '检查中' : '未知', title: '' }
  if (s === 'UP') return { cls: 'bg-ok', text: 'UP', title: '健康检查通过' }
  return { cls: 'bg-warn', text: s, title: '健康检查未通过' }
})
</script>

<template>
  <aside class="flex w-[212px] shrink-0 flex-col border-r border-line bg-surface">
    <div class="flex items-center gap-2 px-4 py-3.5">
      <div class="grid size-7 place-items-center rounded-lg bg-accent/15 text-accent">
        <Brain class="size-4" />
      </div>
      <div class="min-w-0">
        <div class="truncate text-[13px] font-semibold">AI RAG Agent</div>
        <div class="truncate text-[10.5px] text-faint">控制台</div>
      </div>
    </div>

    <nav class="flex flex-col gap-0.5 px-2 py-1">
      <RouterLink
        v-for="r in nav"
        :key="r.path"
        :to="r.path"
        class="flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-[12.5px] transition"
        :class="
          route.path === r.path
            ? 'bg-accent/12 text-accent'
            : 'text-muted hover:bg-surface2 hover:text-fg'
        "
      >
        <component :is="icons[r.meta!.icon as string]" class="size-4 shrink-0" />
        <span class="truncate">{{ r.meta!.title }}</span>
      </RouterLink>
    </nav>

    <div class="flex-1" />

    <!-- 状态灯：点一下立刻重查，不用等轮询 -->
    <button
      class="mx-2 mb-1 flex items-center gap-2 rounded-lg px-2.5 py-2 text-[11.5px] text-muted transition hover:bg-surface2"
      :title="health.title || '点击立即重查健康状态'"
      @click="check()"
    >
      <i class="size-2 shrink-0 rounded-full" :class="[health.cls, { 'animate-pulse': checking }]" />
      <span>后端 {{ health.text }}</span>
    </button>

    <button
      class="mx-2 mb-2.5 flex items-center gap-2 rounded-lg px-2.5 py-2 text-[11.5px] text-muted transition hover:bg-surface2"
      @click="settingsOpen = true"
    >
      <Settings class="size-4" />
      <span>设置</span>
    </button>
  </aside>
</template>
