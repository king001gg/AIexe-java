<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/common'

const props = withDefaults(defineProps<{ text: string; streaming?: boolean }>(), {
  streaming: false,
})

const root = ref<HTMLElement | null>(null)

/*
 * 流式渲染的核心取舍：
 *
 * 后端每个 token 单独推一个事件，一条 500 字的回答能来 300+ 次更新。
 * 如果每次更新都跑一遍 marked + DOMPurify + 高亮，主线程会被打满、光标闪烁。
 * 所以做两件事：
 *   1. 用 requestAnimationFrame 把一帧内的多次更新合并成一次渲染；
 *   2. **流式期间不做语法高亮** —— 未闭合的代码块本来也高亮不对，
 *      等 done 事件把 streaming 置回 false 再统一高亮一次。
 */
const shown = ref(props.text)
let raf = 0

watch(
  () => props.text,
  (v) => {
    if (!props.streaming) {
      shown.value = v
      return
    }
    if (raf) return
    raf = requestAnimationFrame(() => {
      raf = 0
      shown.value = props.text
    })
  },
)

watch(
  () => props.streaming,
  (s) => {
    if (raf) {
      cancelAnimationFrame(raf)
      raf = 0
    }
    shown.value = props.text
    if (!s) nextTick(enhance)
  },
)

onBeforeUnmount(() => {
  if (raf) cancelAnimationFrame(raf)
})

marked.setOptions({ gfm: true, breaks: true })

const html = computed(() => {
  const raw = marked.parse(shown.value ?? '') as string
  // 模型输出属于不可信内容：必须过 DOMPurify，否则一句 <img onerror> 就能打穿页面
  return DOMPurify.sanitize(raw, { ADD_ATTR: ['target', 'rel'] })
})

/* 渲染后处理：给代码块加「语言标签 + 复制按钮」头，并给外链补 target/rel。
 * 用 DOM 后处理而不是正则改 HTML 字符串 —— 后者遇到嵌套 <pre> 就会错。 */
function enhance() {
  const el = root.value
  if (!el) return

  el.querySelectorAll('a[href]').forEach((a) => {
    const href = a.getAttribute('href') || ''
    if (/^https?:/i.test(href)) {
      a.setAttribute('target', '_blank')
      a.setAttribute('rel', 'noopener noreferrer')
    }
  })

  el.querySelectorAll('pre').forEach((pre) => {
    const code = pre.querySelector('code')
    if (!code) return

    if (!props.streaming) {
      // 已经高亮过就跳过，避免每次 enhance 重复着色
      if (code.dataset.hl !== '1') {
        try {
          hljs.highlightElement(code as HTMLElement)
        } catch {
          /* 高亮失败不影响正文，忽略 */
        }
        code.dataset.hl = '1'
      }
    }

    if (pre.dataset.headed === '1') return
    pre.dataset.headed = '1'

    const lang =
      (code.className.match(/language-([\w+#-]+)/)?.[1] || '').trim() || 'text'

    const head = document.createElement('div')
    head.className =
      'flex items-center justify-between border-b border-line px-3 py-1 text-[11px] text-faint select-none'

    const label = document.createElement('span')
    label.className = 'font-mono'
    label.textContent = lang
    head.appendChild(label)

    const btn = document.createElement('button')
    btn.type = 'button'
    btn.className = 'rounded px-1.5 py-0.5 transition hover:bg-surface3 hover:text-fg'
    btn.textContent = '复制'
    btn.setAttribute('aria-label', '复制代码')
    btn.addEventListener('click', () => {
      navigator.clipboard?.writeText(code.textContent || '').then(
        () => {
          btn.textContent = '已复制'
          setTimeout(() => (btn.textContent = '复制'), 1400)
        },
        () => {
          btn.textContent = '复制失败'
          setTimeout(() => (btn.textContent = '复制'), 1400)
        },
      )
    })
    head.appendChild(btn)

    // pre 是 flex 容器后，代码本体需要自己滚
    pre.classList.add('!p-0', 'overflow-hidden')
    pre.insertBefore(head, pre.firstChild)
    ;(code as HTMLElement).classList.add('block', 'px-3', 'py-2.5', 'overflow-x-auto')
  })
}

watch(html, () => nextTick(enhance))
</script>

<template>
  <!-- v-html 的内容已由 DOMPurify 清洗 -->
  <div
    ref="root"
    class="md"
    :class="{ streaming }"
    v-html="html"
  />
</template>
