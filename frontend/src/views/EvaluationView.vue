<script setup lang="ts">
import { CheckCircle2, Play, XCircle } from 'lucide-vue-next'
import { computed, onMounted, ref } from 'vue'
import PageShell from '../components/ui/PageShell.vue'
import Empty from '../components/ui/Empty.vue'
import ErrorBox from '../components/ui/ErrorBox.vue'
import { docsApi, evalApi } from '../api/endpoints'
import { useToast } from '../composables/useToast'
import type { EvaluationReport, KnowledgeBaseSummary } from '../api/types'

const toast = useToast()

const kbs = ref<KnowledgeBaseSummary[]>([])
const knowledgeBaseId = ref<number | undefined>(undefined)
const topK = ref(5)
const running = ref(false)
const report = ref<EvaluationReport | null>(null)
const error = ref('')

const datasetOpen = ref(false)
const dataset = ref<unknown>(null)
const datasetLoading = ref(false)

async function loadKbs() {
  try {
    kbs.value = await docsApi.list()
  } catch {
    /* 知识库列表拿不到不影响跑评测（后端会用默认） */
  }
}

async function run() {
  running.value = true
  error.value = ''
  try {
    // 请求体字段均可省略；只传用户显式选了的，其余走后端配置默认
    report.value = await evalApi.run({
      knowledgeBaseId: knowledgeBaseId.value,
      topK: topK.value,
    })
    if (!report.value.caseCount) {
      toast.warn('评测跑完了，但 0 条用例 —— 检查评测数据集与知识库是否有内容')
    }
  } catch (e) {
    error.value = (e as Error).message
    report.value = null
  } finally {
    running.value = false
  }
}

async function loadDataset() {
  datasetOpen.value = !datasetOpen.value
  if (!datasetOpen.value || dataset.value) return
  datasetLoading.value = true
  try {
    dataset.value = await evalApi.dataset()
  } catch (e) {
    toast.fromError(e, '读取评测数据集失败')
  } finally {
    datasetLoading.value = false
  }
}

const pct = (v: number) => (v == null ? '—' : (v * 100).toFixed(1) + '%')

const metrics = computed(() => {
  const a = report.value?.aggregate
  if (!a) return []
  return [
    { k: 'Recall@K', v: pct(a.recallAtK), raw: a.recallAtK, hint: '相关分块被召回的比例' },
    { k: 'Precision@K', v: pct(a.precisionAtK), raw: a.precisionAtK, hint: '召回结果中真正相关的比例' },
    { k: 'MRR', v: a.mrr.toFixed(3), raw: a.mrr, hint: '首个相关结果的倒数排名均值' },
    { k: 'Hit@K', v: pct(a.hitRate), raw: a.hitRate, hint: '至少命中一条的用例占比' },
  ]
})

onMounted(loadKbs)
</script>

<template>
  <PageShell title="检索评测" desc="对检索链路跑离线评测，作为调参回归基线。注意：只评测检索质量，不评测生成质量。">
    <template #actions>
      <button class="rounded-lg border border-line px-3 py-1.5 text-[12px] text-muted transition hover:text-fg" @click="loadDataset">
        {{ datasetOpen ? '隐藏' : '查看' }}评测集
      </button>
    </template>

    <div class="mx-auto flex max-w-4xl flex-col gap-4">
      <!-- 参数 -->
      <div class="flex flex-wrap items-end gap-3 rounded-xl border border-line bg-surface p-4">
        <label class="flex flex-col gap-1.5">
          <span class="text-[12px] text-muted">知识库</span>
          <select
            v-model="knowledgeBaseId"
            class="rounded-lg border border-line bg-surface2 px-3 py-2 text-[12.5px] outline-none focus:border-accent"
          >
            <option :value="undefined">全部（后端默认）</option>
            <option v-for="k in kbs" :key="k.id" :value="k.id">#{{ k.id }} {{ k.name }}</option>
          </select>
        </label>

        <label class="flex flex-col gap-1.5">
          <span class="text-[12px] text-muted">topK</span>
          <select
            v-model.number="topK"
            class="rounded-lg border border-line bg-surface2 px-3 py-2 text-[12.5px] outline-none focus:border-accent"
          >
            <option v-for="n in [3, 5, 10, 20]" :key="n" :value="n">{{ n }}</option>
          </select>
        </label>

        <button
          class="flex items-center gap-1.5 rounded-lg bg-accent px-4 py-2 text-[12.5px] font-medium text-white transition hover:brightness-110 disabled:opacity-50"
          :disabled="running"
          @click="run"
        >
          <Play class="size-3.5" /> {{ running ? '评测中…' : '运行评测' }}
        </button>
      </div>

      <!-- 数据集 -->
      <div v-if="datasetOpen" class="rounded-xl border border-line bg-surface p-4">
        <div class="mb-2 text-[12.5px] font-medium">当前生效的评测数据集（ground truth）</div>
        <div v-if="datasetLoading" class="text-[12px] text-faint">加载中…</div>
        <pre v-else class="max-h-72 overflow-auto rounded-lg bg-[#0d1017] p-3 font-mono text-[11px] leading-relaxed">{{
          JSON.stringify(dataset, null, 2)
        }}</pre>
      </div>

      <ErrorBox v-if="error" :message="'评测失败：' + error" @retry="run" />

      <template v-else-if="report">
        <!-- 汇总指标 -->
        <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <div v-for="m in metrics" :key="m.k" class="rounded-xl border border-line bg-surface p-3.5" :title="m.hint">
            <div class="text-[11.5px] text-faint">{{ m.k }}</div>
            <div class="mt-1 text-[20px] font-semibold tabular-nums">{{ m.v }}</div>
            <div class="mt-1.5 h-1 overflow-hidden rounded-full bg-surface3">
              <div class="h-full rounded-full bg-accent" :style="{ width: Math.min(100, (m.raw ?? 0) * 100) + '%' }" />
            </div>
          </div>
        </div>

        <div class="flex flex-wrap gap-x-5 gap-y-1 text-[11.5px] text-faint">
          <span>用例 {{ report.caseCount }} 条</span>
          <span>topK {{ report.topK }}</span>
          <span class="font-mono">{{ report.dataset }}</span>
          <span>开始于 {{ report.startedAt }}</span>
        </div>

        <div v-if="report.notes?.length" class="rounded-lg border border-info/30 bg-info/8 px-3.5 py-2.5 text-[11.5px] leading-relaxed text-info">
          <div v-for="(n, i) in report.notes" :key="i">· {{ n }}</div>
        </div>

        <!-- 用例明细 -->
        <div class="overflow-hidden rounded-xl border border-line">
          <table class="w-full text-[12px]">
            <thead class="bg-surface2 text-[11px] text-faint">
              <tr>
                <th class="px-3 py-2 text-left font-medium">用例</th>
                <th class="px-3 py-2 text-left font-medium">问题</th>
                <th class="px-3 py-2 text-right font-medium">召回/期望</th>
                <th class="px-3 py-2 text-right font-medium">首个相关</th>
                <th class="px-3 py-2 text-right font-medium">Recall</th>
                <th class="px-3 py-2 text-right font-medium">RR</th>
                <th class="px-3 py-2 text-center font-medium">命中</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in report.cases" :key="c.caseId" class="border-t border-line-soft">
                <td class="px-3 py-2 font-mono text-[11px] text-faint">{{ c.caseId }}</td>
                <td class="max-w-[280px] px-3 py-2">
                  <div class="truncate" :title="c.question">{{ c.question }}</div>
                  <div v-if="c.missing?.length" class="mt-0.5 truncate text-[10.5px] text-warn" :title="c.missing.join(' / ')">
                    漏召：{{ c.missing.join(' / ') }}
                  </div>
                </td>
                <td class="px-3 py-2 text-right tabular-nums">{{ c.satisfiedCount }}/{{ c.expectedCount }}</td>
                <td class="px-3 py-2 text-right tabular-nums">{{ c.firstRelevantRank || '—' }}</td>
                <td class="px-3 py-2 text-right tabular-nums">{{ pct(c.recall) }}</td>
                <td class="px-3 py-2 text-right tabular-nums">{{ c.reciprocalRank.toFixed(2) }}</td>
                <td class="px-3 py-2 text-center">
                  <CheckCircle2 v-if="c.hit" class="inline size-3.5 text-ok" />
                  <XCircle v-else class="inline size-3.5 text-danger" />
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <Empty
        v-else
        text="还没有跑过评测"
        hint="点「运行评测」用后端配置的 ground truth 跑一遍。若知识库里是空的，会得到 0 条用例 —— 那是预期结果，不是故障。"
      />
    </div>
  </PageShell>
</template>
