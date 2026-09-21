<script setup lang="ts">
import { Save, Trash2 } from 'lucide-vue-next'
import { onMounted, ref } from 'vue'
import PageShell from '../components/ui/PageShell.vue'
import Empty from '../components/ui/Empty.vue'
import ErrorBox from '../components/ui/ErrorBox.vue'
import { memoryApi } from '../api/endpoints'
import { useToast } from '../composables/useToast'
import type { Memory } from '../api/types'

const toast = useToast()
const LS_SID = 'airag.sessionId'

/** 默认跟对话页用同一个会话，这样「对话里让它记住的事」在这能直接看到 */
const sessionId = ref(localStorage.getItem(LS_SID) || 'default')

const items = ref<Memory[]>([])
const loading = ref(false)
const error = ref('')

const form = ref({ key: '', value: '' })
const saving = ref(false)

async function load() {
  if (!sessionId.value.trim()) return
  loading.value = true
  error.value = ''
  try {
    items.value = await memoryApi.list(sessionId.value.trim())
  } catch (e) {
    error.value = (e as Error).message
    items.value = []
  } finally {
    loading.value = false
  }
}

async function save() {
  const key = form.value.key.trim()
  const value = form.value.value.trim()
  if (!key || !value) {
    toast.warn('key 与 value 都不能为空')
    return
  }
  saving.value = true
  try {
    const r = await memoryApi.save({ sessionId: sessionId.value.trim(), key, value })
    if (r.success) {
      toast.ok('已写入长期记忆')
      form.value = { key: '', value: '' }
      load()
    } else {
      toast.err(r.message || '写入失败')
    }
  } catch (e) {
    toast.fromError(e, '写入记忆失败')
  } finally {
    saving.value = false
  }
}

async function remove(m: Memory) {
  try {
    await memoryApi.remove(m.sessionId, m.key)
    toast.ok(`已删除 ${m.key}`)
    load()
  } catch (e) {
    toast.fromError(e, '删除失败')
  }
}

async function clearAll() {
  try {
    await memoryApi.clear(sessionId.value.trim())
    toast.ok('已清空该会话的全部记忆')
    load()
  } catch (e) {
    toast.fromError(e, '清空失败')
  }
}

onMounted(load)
</script>

<template>
  <PageShell title="长期记忆" desc="按 sessionId + key 存储的跨会话键值记忆。读取优先走 Redis 缓存，未命中再回落到数据库。">
    <template #actions>
      <button
        v-if="items.length"
        class="rounded-lg border border-danger/40 px-3 py-1.5 text-[12px] text-danger transition hover:bg-danger/10"
        @click="clearAll"
      >
        清空本会话
      </button>
      <button class="rounded-lg border border-line px-3 py-1.5 text-[12px] text-muted transition hover:text-fg" @click="load">
        刷新
      </button>
    </template>

    <div class="mx-auto flex max-w-3xl flex-col gap-4">
      <!-- 会话选择 -->
      <label class="flex flex-col gap-1.5">
        <span class="text-[12px] text-muted">会话 ID</span>
        <div class="flex gap-2">
          <input
            v-model="sessionId"
            spellcheck="false"
            class="min-w-0 flex-1 rounded-lg border border-line bg-surface2 px-3 py-2 font-mono text-[12px] outline-none focus:border-accent"
            @keydown.enter="load"
          />
          <button class="shrink-0 rounded-lg border border-line px-3.5 py-2 text-[12.5px] text-muted transition hover:text-fg" @click="load">
            查询
          </button>
        </div>
      </label>

      <!-- 新增 -->
      <div class="rounded-xl border border-line bg-surface p-4">
        <div class="mb-2.5 text-[12.5px] font-medium">写入一条记忆</div>
        <div class="flex flex-col gap-2">
          <input
            v-model="form.key"
            placeholder="key，例如 user_name"
            spellcheck="false"
            class="rounded-lg border border-line bg-surface2 px-3 py-2 font-mono text-[12px] outline-none focus:border-accent"
          />
          <textarea
            v-model="form.value"
            rows="2"
            placeholder="value"
            class="resize-none rounded-lg border border-line bg-surface2 px-3 py-2 text-[12.5px] outline-none focus:border-accent"
          />
          <div class="flex justify-end">
            <button
              class="flex items-center gap-1.5 rounded-lg bg-accent px-3.5 py-1.5 text-[12.5px] font-medium text-white transition hover:brightness-110 disabled:opacity-50"
              :disabled="saving"
              @click="save"
            >
              <Save class="size-3.5" /> 保存
            </button>
          </div>
        </div>
      </div>

      <!-- 列表 -->
      <ErrorBox v-if="error" :message="'读取记忆失败：' + error" @retry="load" />

      <div v-else-if="loading" class="text-[12px] text-faint">加载中…</div>

      <Empty
        v-else-if="!items.length"
        text="该会话还没有任何长期记忆"
        hint="在对话里让模型「记住」某些信息，或直接用上方表单写入。"
      />

      <div v-else class="overflow-hidden rounded-xl border border-line">
        <div
          v-for="m in items"
          :key="m.id"
          class="group flex items-start gap-3 border-b border-line-soft bg-surface px-3.5 py-3 last:border-b-0"
        >
          <code class="w-40 shrink-0 truncate font-mono text-[12px] text-accent" :title="m.key">{{ m.key }}</code>
          <div class="min-w-0 flex-1">
            <div class="text-[12.5px] whitespace-pre-wrap break-words">{{ m.value }}</div>
            <div class="mt-1 text-[10.5px] text-faint">更新于 {{ m.updatedAt }}</div>
          </div>
          <button
            class="shrink-0 rounded p-1.5 text-faint opacity-0 transition group-hover:opacity-100 hover:bg-surface3 hover:text-danger focus:opacity-100"
            :aria-label="`删除 ${m.key}`"
            @click="remove(m)"
          >
            <Trash2 class="size-3.5" />
          </button>
        </div>
      </div>
    </div>
  </PageShell>
</template>
