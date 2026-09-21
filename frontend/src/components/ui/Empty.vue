<script setup lang="ts">
import { Inbox } from 'lucide-vue-next'

/*
 * 空/错/载 三态分开：
 * 调研里反复出现的一条教训是「取数失败」被渲染成「空空如也」，
 * 用户以为没数据，实际是请求挂了。所以错误态绝不复用本组件。
 */
defineProps<{ text?: string; hint?: string }>()
</script>

<template>
  <div class="flex flex-col items-center justify-center py-14 text-center">
    <Inbox class="size-7 text-faint/60" />
    <div class="mt-2.5 text-[13px] text-muted">{{ text || '暂无数据' }}</div>
    <div v-if="hint" class="mt-1 max-w-md text-[11.5px] leading-relaxed text-faint">{{ hint }}</div>
    <slot />
  </div>
</template>
