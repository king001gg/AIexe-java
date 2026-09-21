<script setup lang="ts">
import { computed, ref } from 'vue'
import { AlertTriangle, ChevronDown, FileText } from 'lucide-vue-next'
import type { SourceItem } from '../types/ui'

const props = withDefaults(
  defineProps<{
    items: SourceItem[]
    /** 默认展开。流式过程中常希望它自己弹开，答完再收起 */
    open?: boolean
    title?: string
  }>(),
  { open: true, title: '检索来源' },
)

const expanded = ref(props.open)

const num = (v: unknown): number | null => {
  if (v === null || v === undefined || v === '') return null
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : null
}

const rows = computed(() =>
  props.items.map((it) => {
    const score = num(it.score)
    const vr = it.vectorRank ?? 0
    const kr = it.keywordRank ?? 0
    // source 由后端算好（hybrid/vector/keyword）；没给就按排名推
    const route =
      it.source || (vr > 0 && kr > 0 ? 'hybrid' : vr > 0 ? 'vector' : kr > 0 ? 'keyword' : 'unknown')
    return {
      ...it,
      score,
      vectorRank: vr,
      keywordRank: kr,
      route,
      // 是否携带了分路排名信息 —— 决定要不要画泳道
      hasRanks: it.vectorRank !== undefined || it.keywordRank !== undefined,
    }
  }),
)

/** 分数条按本轮最高分归一化（RRF 绝对值没有可比性，只有相对序有意义） */
const maxScore = computed(() => Math.max(...rows.value.map((r) => r.score ?? 0), 0))

const summary = computed(() => {
  const n = { hybrid: 0, vector: 0, keyword: 0, unknown: 0 } as Record<string, number>
  for (const r of rows.value) n[r.route] = (n[r.route] ?? 0) + 1
  return n
})

/** 向量路一条都没召回 —— 这是本项目最需要被看见的退化状态 */
const vectorDead = computed(() => rows.value.length > 0 && summary.value.vector + summary.value.hybrid === 0)

const routeMeta: Record<string, { label: string; cls: string; dot: string }> = {
  hybrid: { label: '双路', cls: 'text-hybrid border-hybrid/40 bg-hybrid/10', dot: 'bg-hybrid' },
  vector: { label: '向量', cls: 'text-vector border-vector/40 bg-vector/10', dot: 'bg-vector' },
  keyword: { label: '关键词', cls: 'text-keyword border-keyword/40 bg-keyword/10', dot: 'bg-keyword' },
  unknown: { label: '未知', cls: 'text-faint border-line bg-surface3', dot: 'bg-faint' },
}
const meta = (r: string) => routeMeta[r] ?? routeMeta.unknown

const pct = (s: number | null) => (maxScore.value > 0 && s != null ? Math.max(6, (s / maxScore.value) * 100) : 0)
</script>

<template>
  <div class="mt-2 overflow-hidden rounded-lg border border-line bg-surface">
    <!-- 头：条数 + 分路汇总。汇总常驻，一眼看出向量路是不是死的 -->
    <button
      class="flex w-full items-center gap-2 px-3 py-2 text-left transition hover:bg-surface2"
      :aria-expanded="expanded"
      @click="expanded = !expanded"
    >
      <ChevronDown class="size-3.5 shrink-0 text-faint transition" :class="{ '-rotate-90': !expanded }" />
      <FileText class="size-3.5 shrink-0 text-faint" />
      <span class="text-[12px] font-medium">{{ title }}</span>
      <span class="text-[12px] text-faint">{{ rows.length }} 条</span>

      <span class="ml-auto flex items-center gap-2.5 text-[11px]">
        <span
          v-for="k in ['hybrid', 'vector', 'keyword']"
          :key="k"
          class="flex items-center gap-1"
          :class="summary[k] ? 'text-muted' : 'text-faint/50'"
          :title="`${routeMeta[k].label}路召回 ${summary[k]} 条`"
        >
          <i class="size-1.5 rounded-full" :class="summary[k] ? routeMeta[k].dot : 'bg-line'" />
          {{ routeMeta[k].label }} {{ summary[k] }}
        </span>
      </span>
    </button>

    <div v-if="expanded" class="border-t border-line">
      <!-- 退化告警：只在这里说一次，而且给出可执行的解释 -->
      <div
        v-if="vectorDead"
        class="flex items-start gap-2 border-b border-warn/25 bg-warn/8 px-3 py-2 text-[11.5px] leading-relaxed text-warn"
      >
        <AlertTriangle class="mt-0.5 size-3.5 shrink-0" />
        <div>
          本轮全部由<strong>关键词路</strong>召回，向量路 0 条。若 embedding 服务不可用
          （例如 DeepSeek 不提供 <code class="font-mono">/embeddings</code>），语义检索会静默失效：
          上传接口仍返回 <code class="font-mono">success:true</code>，但一条向量都没写进去。
          可到「知识库 → 检索诊断」用同一句话对照两路排名确认。
        </div>
      </div>

      <div v-if="!rows.length" class="px-3 py-3 text-[12px] text-faint">本轮没有召回任何片段。</div>

      <div v-for="(r, i) in rows" :key="r.chunkId || r.documentId || i" class="border-b border-line-soft px-3 py-2.5 last:border-b-0">
        <div class="flex items-center gap-2">
          <span class="grid size-4 shrink-0 place-items-center rounded bg-surface3 text-[10px] text-muted">
            {{ i + 1 }}
          </span>
          <span class="rounded border px-1.5 py-px text-[10px]" :class="meta(r.route).cls">
            {{ meta(r.route).label }}
          </span>
          <span class="truncate text-[11px] text-faint" :title="String(r.documentId ?? '')">
            doc {{ r.documentId ?? '-' }}
          </span>

          <span class="ml-auto flex items-center gap-2">
            <span class="font-mono text-[10.5px] text-faint">
              RRF {{ r.score != null ? r.score.toFixed(6) : '-' }}
            </span>
            <span class="h-1.5 w-14 overflow-hidden rounded-full bg-surface3">
              <span class="block h-full rounded-full" :class="meta(r.route).dot" :style="{ width: pct(r.score) + '%' }" />
            </span>
          </span>
        </div>

        <!-- 分路泳道：只有后端带了排名才画（当前 SSE 不带，检索诊断页带） -->
        <div v-if="r.hasRanks" class="mt-1.5 flex flex-wrap gap-x-3 gap-y-1 text-[10.5px]">
          <span class="flex items-center gap-1.5">
            <i class="size-1.5 rounded-full bg-vector" />
            <span :class="r.vectorRank > 0 ? 'text-vector' : 'text-faint/60'">
              向量路 {{ r.vectorRank > 0 ? '#' + r.vectorRank : '未召回' }}
            </span>
          </span>
          <span class="flex items-center gap-1.5">
            <i class="size-1.5 rounded-full bg-keyword" />
            <span :class="r.keywordRank > 0 ? 'text-keyword' : 'text-faint/60'">
              关键词路 {{ r.keywordRank > 0 ? '#' + r.keywordRank : '未召回' }}
            </span>
          </span>
        </div>

        <!-- 刻意用纯文本渲染：这里要看的是「被向量化/被检索的原始片段」，
             按 markdown 渲染会让人分不清哪些是原文、哪些是模型排版 -->
        <div v-if="r.preview || r.title" class="mt-1.5 line-clamp-3 whitespace-pre-wrap text-[11.5px] leading-relaxed text-muted">
          {{ r.preview || r.title }}
        </div>
      </div>
    </div>
  </div>
</template>
