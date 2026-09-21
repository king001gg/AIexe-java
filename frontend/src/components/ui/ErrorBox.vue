<script setup lang="ts">
import { AlertTriangle, RefreshCw } from 'lucide-vue-next'

defineProps<{ message: string; detail?: string }>()
defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="flex items-start gap-2.5 rounded-lg border border-danger/40 bg-danger/8 px-3.5 py-3">
    <AlertTriangle class="mt-0.5 size-4 shrink-0 text-danger" />
    <div class="min-w-0 flex-1">
      <div class="text-[12.5px] text-danger">{{ message }}</div>
      <div v-if="detail" class="mt-1 font-mono text-[11px] break-all text-faint">{{ detail }}</div>
    </div>
    <button
      class="shrink-0 rounded border border-line px-2 py-1 text-[11px] text-muted transition hover:text-fg"
      @click="$emit('retry')"
    >
      <RefreshCw class="size-3" />
    </button>
  </div>
</template>
