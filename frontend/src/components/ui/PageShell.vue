<script setup lang="ts">
defineProps<{ title: string; desc?: string }>()
</script>

<template>
  <div class="flex min-h-0 flex-1 flex-col">
    <header class="flex shrink-0 items-center gap-3 border-b border-line px-5 py-3">
      <div class="min-w-0">
        <h1 class="text-[14.5px] font-semibold">{{ title }}</h1>
        <p v-if="desc" class="mt-0.5 text-[11.5px] text-faint">{{ desc }}</p>
      </div>
      <span class="flex-1" />
      <slot name="actions" />
    </header>

    <div class="min-h-0 flex-1 overflow-y-auto p-5">
      <slot />
    </div>
  </div>
</template>
