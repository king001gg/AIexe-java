<script setup lang="ts">
import { ref, watch } from 'vue'
import Modal from './ui/Modal.vue'
import { applySettings, baseUrl as curBase, apiKey as curKey } from '../composables/useSettings'
import { useToast } from '../composables/useToast'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()
const toast = useToast()

const base = ref(curBase.value)
const key = ref(curKey.value)

watch(
  () => props.open,
  (v) => {
    if (v) {
      base.value = curBase.value
      key.value = curKey.value
    }
  },
)

function save() {
  applySettings({ baseUrl: base.value, apiKey: key.value })
  toast.ok('设置已保存')
  emit('close')
}

function reset() {
  base.value = '/api'
  key.value = ''
}
</script>

<template>
  <Modal :open="open" title="设置" width="600px" @close="emit('close')">
    <div class="flex flex-col gap-4">
      <label class="flex flex-col gap-1.5">
        <span class="text-[12.5px] font-medium">API 根地址</span>
        <input
          v-model="base"
          spellcheck="false"
          class="rounded-lg border border-line bg-surface2 px-3 py-2 font-mono text-[12px] outline-none focus:border-accent"
        />
        <span class="text-[11px] leading-relaxed text-faint">
          <code class="font-mono">/api</code> 是相对路径，由 Vite 开发代理转发到
          <code class="font-mono">http://localhost:8080</code>，浏览器全程同源，<strong>不走 CORS</strong>。
          仅当把构建产物放到别的源、后端在另一个地址时才需要改成绝对地址
          （例如 <code class="font-mono">http://localhost:8080/api</code>）——前提是该源在后端
          <code class="font-mono">LoggingConfig</code> 的 CORS 白名单里。
        </span>
      </label>

      <label class="flex flex-col gap-1.5">
        <span class="text-[12.5px] font-medium">API Key</span>
        <input
          v-model="key"
          type="password"
          spellcheck="false"
          placeholder="留空则不发送 X-API-Key 头"
          class="rounded-lg border border-line bg-surface2 px-3 py-2 font-mono text-[12px] outline-none focus:border-accent"
        />
        <span class="text-[11px] leading-relaxed text-faint">
          仅当后端 <code class="font-mono">security.enabled=true</code> 时需要
          （dev / test profile 与容器里是开的；<code class="font-mono">local</code> profile 关闭，可留空）。
          请求会带上 <code class="font-mono">X-API-Key</code> 头。
        </span>
      </label>

      <div class="rounded-lg border border-line bg-surface2 px-3 py-2 text-[11.5px] leading-relaxed text-faint">
        <div class="mb-1 font-medium text-muted">关于 CORS</div>
        后端白名单只有 <code class="font-mono">localhost:3000</code>、
        <code class="font-mono">localhost:8501</code>、<code class="font-mono">localhost:8080</code> 三个来源。
        用 <code class="font-mono">file://</code> 直接打开本页会被浏览器拦掉（Origin 为
        <code class="font-mono">null</code>，不在白名单内），必须由一个静态服务器提供。
      </div>
    </div>

    <template #footer>
      <div class="mt-4 flex justify-end gap-2">
        <button class="rounded-lg border border-line px-3 py-1.5 text-[12.5px] text-muted transition hover:text-fg" @click="reset">
          恢复默认
        </button>
        <button class="rounded-lg bg-accent px-3.5 py-1.5 text-[12.5px] font-medium text-white transition hover:brightness-110" @click="save">
          保存
        </button>
      </div>
    </template>
  </Modal>
</template>
