<script setup lang="ts">
import { Activity, KeyRound, Link2, ListTree, RefreshCw } from 'lucide-vue-next'
import { onMounted, ref } from 'vue'
import PageShell from '../components/ui/PageShell.vue'
import ErrorBox from '../components/ui/ErrorBox.vue'
import { http, getApiKey, getBaseUrl } from '../api/client'
import { useHealth } from '../composables/useHealth'
import { settingsOpen } from '../composables/useUi'
import { PROXY_TARGET } from '../composables/useSettings'

const { status, error, checking, check } = useHealth()

interface IndexBody {
  application?: string
  message?: string
  note?: string
  endpoints?: Record<string, string>
}
const index = ref<IndexBody | null>(null)
const indexError = ref('')

const base = ref(getBaseUrl())
const hasKey = ref(!!getApiKey())

async function loadIndex() {
  try {
    index.value = await http.get<IndexBody>('/')
    indexError.value = ''
  } catch (e) {
    indexError.value = (e as Error).message
    index.value = null
  }
}

function refreshAll() {
  check()
  loadIndex()
  base.value = getBaseUrl()
  hasKey.value = !!getApiKey()
}

onMounted(loadIndex)

const dot = (s?: string) =>
  s === 'UP' ? 'bg-ok' : !s ? 'bg-faint' : s === 'DOWN' ? 'bg-danger' : 'bg-warn'
</script>

<template>
  <PageShell title="运行状态" desc="健康检查、运行时配置与后端自述的接口清单。">
    <template #actions>
      <button
        class="flex items-center gap-1.5 rounded-lg border border-line px-3 py-1.5 text-[12px] text-muted transition hover:text-fg"
        @click="refreshAll"
      >
        <RefreshCw class="size-3.5" :class="{ 'animate-spin': checking }" /> 刷新
      </button>
    </template>

    <div class="mx-auto flex max-w-3xl flex-col gap-4">
      <!-- 健康 -->
      <section class="rounded-xl border border-line bg-surface p-4">
        <div class="mb-3 flex items-center gap-2">
          <Activity class="size-4 text-accent" />
          <span class="text-[13px] font-medium">健康检查</span>
          <code class="font-mono text-[11px] text-faint">GET {{ base }}/actuator/health</code>
        </div>

        <ErrorBox v-if="error" :message="'健康检查失败：' + error" @retry="check" />

        <template v-else-if="status">
          <div class="flex items-center gap-2.5">
            <i class="size-2.5 rounded-full" :class="dot(status.status)" />
            <span class="text-[15px] font-semibold">{{ status.status }}</span>
          </div>

          <div v-if="status.components && Object.keys(status.components).length" class="mt-3 flex flex-wrap gap-2">
            <div
              v-for="(v, k) in status.components"
              :key="k"
              class="flex items-center gap-1.5 rounded-lg border border-line bg-surface2 px-2.5 py-1.5 text-[11.5px]"
            >
              <i class="size-1.5 rounded-full" :class="dot(v.status)" />
              <span class="text-muted">{{ k }}</span>
              <span class="text-faint">{{ v.status }}</span>
            </div>
          </div>
          <p class="mt-3 text-[11px] leading-relaxed text-faint">
            这个端点在 security 开启时也属于 permit-all，不需要 API Key。
            若某项组件长期 DOWN，注意代码里对 Redis / Milvus 的失败都是「降级 + WARN」而非启动失败，
            健康检查 UP 并不等于向量检索真的在工作。
          </p>
        </template>

        <div v-else class="text-[12px] text-faint">检查中…</div>
      </section>

      <!-- 运行时配置 -->
      <section class="rounded-xl border border-line bg-surface p-4">
        <div class="mb-3 flex items-center gap-2">
          <Link2 class="size-4 text-accent" />
          <span class="text-[13px] font-medium">运行时配置</span>
        </div>
        <dl class="grid gap-2.5 text-[12px] sm:grid-cols-[120px_1fr]">
          <dt class="text-faint">API 根地址</dt>
          <dd class="font-mono break-all">{{ base }}</dd>

          <dt class="text-faint">实际请求前缀</dt>
          <dd class="font-mono break-all text-muted">
            {{ base === '/api' ? `同源 /api/* → Vite 代理转发到 ${PROXY_TARGET}` : base + '/*（直连，需在 CORS 白名单内）' }}
          </dd>

          <dt class="flex items-center gap-1 text-faint"><KeyRound class="size-3" /> API Key</dt>
          <dd>
            <span v-if="hasKey" class="text-ok">已设置（请求带 X-API-Key 头）</span>
            <span v-else class="text-faint">未设置 —— local profile 关闭了认证，可直接用</span>
          </dd>
        </dl>
        <button class="mt-3 rounded-lg border border-line px-3 py-1.5 text-[12px] text-muted transition hover:text-fg" @click="settingsOpen = true">
          修改设置
        </button>
      </section>

      <!-- 后端接口清单 -->
      <section class="rounded-xl border border-line bg-surface p-4">
        <div class="mb-3 flex items-center gap-2">
          <ListTree class="size-4 text-accent" />
          <span class="text-[13px] font-medium">后端自述的接口清单</span>
          <code class="font-mono text-[11px] text-faint">GET {{ base }}/</code>
        </div>

        <ErrorBox v-if="indexError" :message="'读取接口清单失败：' + indexError" @retry="loadIndex" />

        <template v-else-if="index">
          <p v-if="index.message" class="text-[12px] text-muted">{{ index.message }}</p>
          <p v-if="index.note" class="mt-1 text-[11.5px] text-faint">{{ index.note }}</p>
          <div class="mt-3 overflow-hidden rounded-lg border border-line">
            <div
              v-for="(desc, ep) in index.endpoints"
              :key="ep"
              class="flex items-baseline gap-3 border-b border-line-soft px-3 py-1.5 last:border-b-0"
            >
              <code class="shrink-0 font-mono text-[11.5px] text-accent">{{ ep }}</code>
              <span class="min-w-0 flex-1 text-right text-[11.5px] text-faint">{{ desc }}</span>
            </div>
          </div>
        </template>

        <div v-else class="text-[12px] text-faint">加载中…</div>
      </section>
    </div>
  </PageShell>
</template>
