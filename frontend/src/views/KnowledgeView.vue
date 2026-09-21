<script setup lang="ts">
import { Database, FileUp, Search, Trash2, Upload } from 'lucide-vue-next'
import { computed, onMounted, ref } from 'vue'
import PageShell from '../components/ui/PageShell.vue'
import Modal from '../components/ui/Modal.vue'
import Empty from '../components/ui/Empty.vue'
import ErrorBox from '../components/ui/ErrorBox.vue'
import SourcePanel from '../components/SourcePanel.vue'
import { docsApi } from '../api/endpoints'
import { useToast } from '../composables/useToast'
import type { DetailedSearchResult, DocumentSummary, KnowledgeBaseSummary } from '../api/types'

const toast = useToast()

const kbs = ref<KnowledgeBaseSummary[]>([])
const loading = ref(false)
const error = ref('')

const selected = ref<KnowledgeBaseSummary | null>(null)
const tab = ref<'chunks' | 'retrieval'>('retrieval')

const chunks = ref<DocumentSummary[]>([])
const chunksLoading = ref(false)

/* ── 检索诊断 ── */
const query = ref('')
const topK = ref(5)
const searching = ref(false)
const result = ref<DetailedSearchResult | null>(null)
const searchError = ref('')

/* ── 上传 ── */
const uploadOpen = ref(false)
const uploading = ref(false)
const file = ref<File | null>(null)
const form = ref({ knowledgeBaseName: '', description: '', createdBy: '', chunkSize: 1000, chunkOverlap: 200 })

async function loadKbs() {
  loading.value = true
  error.value = ''
  try {
    kbs.value = await docsApi.list()
    if (selected.value) {
      selected.value = kbs.value.find((k) => k.id === selected.value!.id) ?? null
    }
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

async function pick(kb: KnowledgeBaseSummary) {
  selected.value = kb
  result.value = null
  searchError.value = ''
  await loadChunks(kb.id)
}

async function loadChunks(id: number) {
  chunksLoading.value = true
  try {
    chunks.value = await docsApi.chunks(id)
  } catch (e) {
    toast.fromError(e, '读取文档分块失败')
    chunks.value = []
  } finally {
    chunksLoading.value = false
  }
}

async function runSearch() {
  const q = query.value.trim()
  if (!q) return
  searching.value = true
  searchError.value = ''
  try {
    result.value = await docsApi.searchDetailed(q, selected.value?.id, topK.value)
  } catch (e) {
    searchError.value = (e as Error).message
    result.value = null
  } finally {
    searching.value = false
  }
}

async function removeKb(kb: KnowledgeBaseSummary) {
  if (!confirm(`删除知识库「${kb.name}」及其全部文档分块？此操作不可撤销。`)) return
  try {
    await docsApi.remove(kb.id)
    toast.ok(`已删除知识库 ${kb.name}`)
    if (selected.value?.id === kb.id) selected.value = null
    loadKbs()
  } catch (e) {
    toast.fromError(e, '删除失败')
  }
}

function onFile(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0] ?? null
  file.value = f
  if (f && !form.value.knowledgeBaseName) {
    form.value.knowledgeBaseName = f.name.replace(/\.[^.]+$/, '')
  }
}

async function submitUpload() {
  if (!file.value) {
    toast.warn('请先选择文件')
    return
  }
  if (!form.value.knowledgeBaseName.trim()) {
    toast.warn('知识库名称不能为空（后端 @NotBlank 校验）')
    return
  }
  uploading.value = true
  try {
    const r = await docsApi.upload({
      file: file.value,
      knowledgeBaseName: form.value.knowledgeBaseName.trim(),
      description: form.value.description || undefined,
      createdBy: form.value.createdBy || undefined,
      chunkSize: form.value.chunkSize,
      chunkOverlap: form.value.chunkOverlap,
    })

    if (r.success) {
      toast.ok(
        `上传成功：${r.documentCount ?? '?'} 个分块，${r.totalTokens ?? '?'} tokens`,
        '注意：success:true 只代表文本已入库分块；向量是否真的写入取决于 embedding 服务是否可用。',
      )
      uploadOpen.value = false
      file.value = null
      form.value = { knowledgeBaseName: '', description: '', createdBy: '', chunkSize: 1000, chunkOverlap: 200 }
      await loadKbs()
    } else {
      toast.err(r.message || '上传失败')
    }
  } catch (e) {
    toast.fromError(e, '上传失败')
  } finally {
    uploading.value = false
  }
}

const totalChunks = computed(() => kbs.value.reduce((s, k) => s + (k.docCount ?? 0), 0))

onMounted(loadKbs)
</script>

<template>
  <PageShell title="知识库" desc="上传文档、查看分块，并用「检索诊断」观察向量路与关键词路各自的召回情况。">
    <template #actions>
      <button class="rounded-lg border border-line px-3 py-1.5 text-[12px] text-muted transition hover:text-fg" @click="loadKbs">
        刷新
      </button>
      <button
        class="flex items-center gap-1.5 rounded-lg bg-accent px-3 py-1.5 text-[12px] font-medium text-white transition hover:brightness-110"
        @click="uploadOpen = true"
      >
        <Upload class="size-3.5" /> 上传文档
      </button>
    </template>

    <ErrorBox v-if="error" :message="'加载知识库失败：' + error" @retry="loadKbs" />

    <div v-else class="grid gap-4 lg:grid-cols-[260px_1fr]">
      <!-- 知识库列表 -->
      <div class="flex flex-col gap-1.5">
        <div class="flex items-center justify-between px-1 pb-1 text-[11.5px] text-faint">
          <span>{{ kbs.length }} 个知识库</span>
          <span>{{ totalChunks }} 个分块</span>
        </div>

        <div v-if="loading" class="px-1 text-[12px] text-faint">加载中…</div>
        <Empty v-else-if="!kbs.length" text="还没有知识库" hint="上传一个文档就会自动创建。" />

        <button
          v-for="kb in kbs"
          :key="kb.id"
          class="group flex items-start gap-2.5 rounded-lg border px-3 py-2.5 text-left transition"
          :class="
            selected?.id === kb.id
              ? 'border-accent/60 bg-accent/10'
              : 'border-line bg-surface hover:border-accent/40'
          "
          @click="pick(kb)"
        >
          <Database class="mt-0.5 size-4 shrink-0" :class="selected?.id === kb.id ? 'text-accent' : 'text-faint'" />
          <div class="min-w-0 flex-1">
            <div class="truncate text-[12.5px]" :class="selected?.id === kb.id ? 'text-accent' : 'text-fg'">
              {{ kb.name }}
            </div>
            <div class="mt-0.5 text-[10.5px] text-faint">
              #{{ kb.id }} · {{ kb.docCount ?? 0 }} 分块
              <template v-if="kb.totalTokens"> · {{ kb.totalTokens.toLocaleString('en-US') }} tok</template>
            </div>
          </div>
          <button
            class="shrink-0 rounded p-1 text-faint opacity-0 transition group-hover:opacity-100 hover:text-danger focus:opacity-100"
            :aria-label="`删除 ${kb.name}`"
            @click.stop="removeKb(kb)"
          >
            <Trash2 class="size-3.5" />
          </button>
        </button>
      </div>

      <!-- 详情 -->
      <div class="min-w-0">
        <Empty
          v-if="!selected"
          text="选择左侧一个知识库"
          hint="选中后可以查看它的分块，或对整库做一次检索诊断。"
        />

        <div v-else class="flex flex-col gap-3">
          <div class="flex items-center gap-2 border-b border-line">
            <button
              v-for="t in [
                { k: 'retrieval', label: '检索诊断' },
                { k: 'chunks', label: `分块 (${chunks.length})` },
              ]"
              :key="t.k"
              class="-mb-px border-b-2 px-3 py-2 text-[12.5px] transition"
              :class="tab === t.k ? 'border-accent text-accent' : 'border-transparent text-muted hover:text-fg'"
              @click="t.k === 'chunks' ? ((tab = 'chunks'), loadChunks(selected!.id)) : (tab = 'retrieval')"
            >
              {{ t.label }}
            </button>
          </div>

          <!-- 检索诊断 -->
          <div v-if="tab === 'retrieval'" class="flex flex-col gap-3">
            <div class="rounded-xl border border-line bg-surface p-4">
              <div class="flex gap-2">
                <input
                  v-model="query"
                  placeholder="输入一句话，看它在两路上分别被怎么召回"
                  spellcheck="false"
                  class="min-w-0 flex-1 rounded-lg border border-line bg-surface2 px-3 py-2 text-[12.5px] outline-none focus:border-accent"
                  @keydown.enter="runSearch"
                />
                <select
                  v-model.number="topK"
                  class="shrink-0 rounded-lg border border-line bg-surface2 px-2 py-2 text-[12px] outline-none focus:border-accent"
                >
                  <option v-for="n in [3, 5, 10, 20]" :key="n" :value="n">topK {{ n }}</option>
                </select>
                <button
                  class="flex shrink-0 items-center gap-1.5 rounded-lg bg-accent px-3.5 py-2 text-[12.5px] font-medium text-white transition hover:brightness-110 disabled:opacity-50"
                  :disabled="searching || !query.trim()"
                  @click="runSearch"
                >
                  <Search class="size-3.5" /> 检索
                </button>
              </div>
              <p class="mt-2 text-[11px] leading-relaxed text-faint">
                分数是 <strong>RRF 融合分</strong>（越高越相关），不是余弦相似度。向量路 / 关键词路显示各自的
                1-based 排名，<strong>「未召回」表示该路完全没返回这个分块</strong>。
                若每条都显示向量路未召回，说明 embedding 服务当前不可用。
              </p>
            </div>

            <ErrorBox v-if="searchError" :message="'检索失败：' + searchError" @retry="runSearch" />

            <div v-else-if="result">
              <div class="mb-2 flex items-center gap-3 text-[11.5px] text-faint">
                <span>命中 {{ result.count }} 条</span>
                <span class="font-mono">「{{ result.query }}」</span>
              </div>
              <SourcePanel
                v-if="result.hits.length"
                :items="result.hits.map((h) => ({ ...h, preview: h.title }))"
                title="召回明细"
                :open="true"
              />
              <Empty v-else text="没有召回任何分块" hint="换一个更贴近原文用词的说法试试；关键词路对用词很敏感。" />
            </div>

            <Empty v-else text="还没有检索" hint="输入一句话开始；建议先用原文里出现过的词，再用同义改写，对比两次结果。" />
          </div>

          <!-- 分块列表 -->
          <div v-else-if="tab === 'chunks'">
            <div v-if="chunksLoading" class="text-[12px] text-faint">加载中…</div>
            <Empty v-else-if="!chunks.length" text="这个知识库没有分块" />
            <div v-else class="flex flex-col gap-2">
              <div v-for="c in chunks" :key="c.id" class="rounded-lg border border-line bg-surface p-3">
                <div class="flex flex-wrap items-center gap-2 text-[10.5px] text-faint">
                  <span class="rounded bg-surface3 px-1.5 py-px">#{{ c.chunkIndex }}</span>
                  <span class="font-mono">{{ c.chunkId }}</span>
                  <span v-if="c.tokens">{{ c.tokens }} tok</span>
                  <span
                    v-if="c.vectorId"
                    class="rounded px-1.5 py-px"
                    :class="/^[0-9a-f-]{36}$/i.test(c.vectorId) ? 'bg-warn/15 text-warn' : 'bg-ok/15 text-ok'"
                    :title="
                      /^[0-9a-f-]{36}$/i.test(c.vectorId)
                        ? '这是 UUID 形状 —— DocumentService 在嵌入失败时用它冒充成功的标识'
                        : '向量库返回的 ID'
                    "
                  >
                    vectorId {{ c.vectorId.slice(0, 12) }}
                  </span>
                </div>
                <div class="mt-1.5 max-h-40 overflow-y-auto text-[12px] leading-relaxed whitespace-pre-wrap text-muted">
                  {{ c.content }}
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 上传 -->
    <Modal :open="uploadOpen" title="上传文档" width="560px" @close="uploadOpen = false">
      <div class="flex flex-col gap-3.5">
        <label class="flex flex-col gap-1.5">
          <span class="text-[12.5px] font-medium">文件 <span class="text-danger">*</span></span>
          <input
            type="file"
            accept=".txt,.md,.pdf,.doc,.docx"
            class="rounded-lg border border-line bg-surface2 px-3 py-2 text-[12px] file:mr-2 file:rounded file:border-0 file:bg-surface3 file:px-2 file:py-1 file:text-fg"
            @change="onFile"
          />
        </label>

        <label class="flex flex-col gap-1.5">
          <span class="text-[12.5px] font-medium">知识库名称 <span class="text-danger">*</span></span>
          <input
            v-model="form.knowledgeBaseName"
            placeholder="同名会并入同一个知识库"
            class="rounded-lg border border-line bg-surface2 px-3 py-2 text-[12.5px] outline-none focus:border-accent"
          />
        </label>

        <div class="grid gap-3.5 sm:grid-cols-2">
          <label class="flex flex-col gap-1.5">
            <span class="text-[12.5px] font-medium">描述</span>
            <input v-model="form.description" class="rounded-lg border border-line bg-surface2 px-3 py-2 text-[12.5px] outline-none focus:border-accent" />
          </label>
          <label class="flex flex-col gap-1.5">
            <span class="text-[12.5px] font-medium">创建者</span>
            <input v-model="form.createdBy" class="rounded-lg border border-line bg-surface2 px-3 py-2 text-[12.5px] outline-none focus:border-accent" />
          </label>
        </div>

        <div class="grid gap-3.5 sm:grid-cols-2">
          <label class="flex flex-col gap-1.5">
            <span class="text-[12.5px] font-medium">分块大小</span>
            <input v-model.number="form.chunkSize" type="number" class="rounded-lg border border-line bg-surface2 px-3 py-2 text-[12.5px] outline-none focus:border-accent" />
          </label>
          <label class="flex flex-col gap-1.5">
            <span class="text-[12.5px] font-medium">分块重叠</span>
            <input v-model.number="form.chunkOverlap" type="number" class="rounded-lg border border-line bg-surface2 px-3 py-2 text-[12.5px] outline-none focus:border-accent" />
          </label>
        </div>

        <div class="rounded-lg border border-warn/30 bg-warn/8 px-3 py-2 text-[11px] leading-relaxed text-warn">
          <FileUp class="mr-1 inline size-3" />
          上传接口返回 <code class="font-mono">success:true</code> 只保证文本已分块入库。
          向量写入失败会被 <code class="font-mono">DocumentService</code> 静默吞掉并返回一个随机 UUID，
          所以请到「检索诊断」确认向量路是否真的有召回。
        </div>
      </div>

      <template #footer>
        <div class="mt-4 flex justify-end gap-2">
          <button class="rounded-lg border border-line px-3 py-1.5 text-[12.5px] text-muted transition hover:text-fg" @click="uploadOpen = false">
            取消
          </button>
          <button
            class="flex items-center gap-1.5 rounded-lg bg-accent px-3.5 py-1.5 text-[12.5px] font-medium text-white transition hover:brightness-110 disabled:opacity-50"
            :disabled="uploading"
            @click="submitUpload"
          >
            <Upload class="size-3.5" /> {{ uploading ? '上传中…' : '上传' }}
          </button>
        </div>
      </template>
    </Modal>
  </PageShell>
</template>
