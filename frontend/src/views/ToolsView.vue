<script setup lang="ts">
import { CalendarClock, CloudSun, Calculator, Play, Search, Sigma, Wrench } from 'lucide-vue-next'
import { onMounted, ref } from 'vue'
import PageShell from '../components/ui/PageShell.vue'
import Empty from '../components/ui/Empty.vue'
import ErrorBox from '../components/ui/ErrorBox.vue'
import { toolsApi } from '../api/endpoints'
import { useToast } from '../composables/useToast'
import type { ToolExecuteResult } from '../api/types'

const toast = useToast()
const tools = ref<string[]>([])
const loading = ref(false)
const error = ref('')

const active = ref('')
const args = ref('')
const running = ref(false)
const result = ref<ToolExecuteResult | null>(null)

const iconOf: Record<string, unknown> = {
  calculator: Calculator,
  math: Sigma,
  search: Search,
  weather: CloudSun,
  datetime: CalendarClock,
}
const icon = (n: string) => iconOf[n] ?? Wrench

/** 每个工具的入参提示 —— 取自各 Tool 类的描述，空串是合法调用（datetime） */
const HINTS: Record<string, string> = {
  calculator: '例如：123 * 456 + 789',
  math: '例如：sqrt(144) 或 2^10',
  search: '例如：Spring Boot 是什么',
  weather: '例如：北京',
  datetime: '可为空 —— 直接执行即返回当前时间',
}
const hint = (n: string) => HINTS[n] || '按该工具的约定传入字符串参数'

async function load() {
  loading.value = true
  error.value = ''
  try {
    tools.value = await toolsApi.list()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

function pick(name: string) {
  active.value = name
  args.value = ''
  result.value = null
}

async function run() {
  if (!active.value || running.value) return
  running.value = true
  result.value = null
  try {
    // 注意：arguments 字段必须存在（值可为空串），否则后端按缺陷 D9 的口径返回 400
    result.value = await toolsApi.execute(active.value, args.value)
  } catch (e) {
    toast.fromError(e, '工具执行请求失败')
  } finally {
    running.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageShell title="工具" desc="执行后端已注册的工具。工具调用的入参必须是字符串，空串是合法值。">
    <template #actions>
      <button class="rounded-lg border border-line px-3 py-1.5 text-[12px] text-muted transition hover:text-fg" @click="load">
        刷新
      </button>
    </template>

    <ErrorBox v-if="error" :message="'加载工具列表失败：' + error" @retry="load" />

    <div v-else class="grid gap-4 lg:grid-cols-[240px_1fr]">
      <!-- 工具列表 -->
      <div class="flex flex-col gap-1.5">
        <div v-if="loading" class="text-[12px] text-faint">加载中…</div>
        <Empty v-else-if="!tools.length" text="没有已注册的工具" />
        <button
          v-for="t in tools"
          :key="t"
          class="flex items-center gap-2.5 rounded-lg border px-3 py-2.5 text-left transition"
          :class="
            active === t
              ? 'border-accent/60 bg-accent/10 text-accent'
              : 'border-line bg-surface text-muted hover:border-accent/40 hover:text-fg'
          "
          @click="pick(t)"
        >
          <component :is="icon(t)" class="size-4 shrink-0" />
          <span class="truncate font-mono text-[12.5px]">{{ t }}</span>
        </button>
      </div>

      <!-- 执行区 -->
      <div class="min-w-0">
        <Empty v-if="!active" text="选择左侧一个工具" hint="工具由后端 ToolRegistryConfig 注册，模型在对话中也会按需调用同一批工具。" />

        <div v-else class="flex flex-col gap-3 rounded-xl border border-line bg-surface p-4">
          <div class="flex items-center gap-2">
            <component :is="icon(active)" class="size-4 text-warn" />
            <code class="font-mono text-[13px]">{{ active }}</code>
          </div>

          <label class="flex flex-col gap-1.5">
            <span class="text-[12px] text-muted">arguments</span>
            <div class="flex gap-2">
              <input
                v-model="args"
                :placeholder="hint(active)"
                spellcheck="false"
                class="min-w-0 flex-1 rounded-lg border border-line bg-surface2 px-3 py-2 font-mono text-[12.5px] outline-none focus:border-accent"
                @keydown.enter="run"
              />
              <button
                class="flex shrink-0 items-center gap-1.5 rounded-lg bg-accent px-3.5 py-2 text-[12.5px] font-medium text-white transition hover:brightness-110 disabled:opacity-50"
                :disabled="running"
                @click="run"
              >
                <Play class="size-3.5" />
                执行
              </button>
            </div>
            <span class="text-[11px] text-faint">{{ hint(active) }}</span>
          </label>

          <div v-if="result" class="rounded-lg border p-3" :class="result.success ? 'border-ok/35 bg-ok/8' : 'border-danger/35 bg-danger/8'">
            <div class="mb-1.5 flex items-center gap-2 text-[11.5px]">
              <span :class="result.success ? 'text-ok' : 'text-danger'">
                {{ result.success ? '执行成功' : '执行失败' }}
              </span>
              <code v-if="result.toolName" class="font-mono text-faint">{{ result.toolName }}</code>
            </div>
            <pre class="overflow-x-auto font-mono text-[12px] whitespace-pre-wrap break-all">{{
              result.success ? result.result : result.message
            }}</pre>
          </div>
        </div>
      </div>
    </div>
  </PageShell>
</template>
