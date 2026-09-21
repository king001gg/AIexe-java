<script setup lang="ts">
import { X } from 'lucide-vue-next'
import { onUnmounted, watch } from 'vue'

const props = defineProps<{ open: boolean; title?: string; width?: string }>()
const emit = defineEmits<{ close: [] }>()

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
}
watch(
  () => props.open,
  (v) => {
    if (v) window.addEventListener('keydown', onKey)
    else window.removeEventListener('keydown', onKey)
  },
)
onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <Teleport to="body">
    <Transition
      enter-active-class="transition duration-150 ease-out"
      enter-from-class="opacity-0"
      leave-active-class="transition duration-100 ease-in"
      leave-to-class="opacity-0"
    >
      <div
        v-if="open"
        class="fixed inset-0 z-40 flex items-start justify-center overflow-y-auto bg-black/60 p-4 pt-[8vh] backdrop-blur-sm"
        @click.self="emit('close')"
      >
        <div
          class="w-full rounded-xl border border-line bg-surface shadow-2xl"
          :style="{ maxWidth: width || '560px' }"
        >
          <div
            v-if="title"
            class="flex items-center justify-between border-b border-line px-4 py-3"
          >
            <h3 class="text-[14px] font-semibold">{{ title }}</h3>
            <button
              class="rounded p-1 text-faint transition hover:bg-surface3 hover:text-fg"
              @click="emit('close')"
            >
              <X class="size-4" />
            </button>
          </div>
          <div class="p-4">
            <slot />
            <slot name="footer" />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
