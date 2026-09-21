<script setup lang="ts">
import { AlertTriangle, CheckCircle2, Info, X, XCircle } from 'lucide-vue-next'
import { useToast, type ToastKind } from '../../composables/useToast'

const { items, dismiss } = useToast()

const look: Record<ToastKind, { icon: unknown; cls: string }> = {
  ok: { icon: CheckCircle2, cls: 'border-ok/40 text-ok' },
  err: { icon: XCircle, cls: 'border-danger/40 text-danger' },
  warn: { icon: AlertTriangle, cls: 'border-warn/40 text-warn' },
  info: { icon: Info, cls: 'border-info/40 text-info' },
}
</script>

<template>
  <div class="fixed bottom-4 right-4 z-50 flex w-[min(420px,calc(100vw-2rem))] flex-col gap-2">
    <TransitionGroup
      enter-active-class="transition duration-200 ease-out"
      enter-from-class="translate-y-2 opacity-0"
      leave-active-class="transition duration-150 ease-in"
      leave-to-class="translate-x-4 opacity-0"
    >
      <div
        v-for="t in items"
        :key="t.id"
        class="flex items-start gap-2.5 rounded-lg border bg-surface2/95 p-3 shadow-xl backdrop-blur"
        :class="look[t.kind].cls"
      >
        <component :is="look[t.kind].icon" class="mt-0.5 size-4 shrink-0" />
        <div class="min-w-0 flex-1">
          <div class="text-[13px] leading-snug text-fg">{{ t.text }}</div>
          <div v-if="t.detail" class="mt-1 font-mono text-[11px] leading-snug text-faint">
            {{ t.detail }}
          </div>
        </div>
        <button
          class="shrink-0 rounded p-0.5 text-faint transition hover:bg-surface3 hover:text-fg"
          title="关闭"
          @click="dismiss(t.id)"
        >
          <X class="size-3.5" />
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>
