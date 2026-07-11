<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import * as markdownItKatexNs from '@vscode/markdown-it-katex'
import 'katex/dist/katex.min.css'
import hljs from 'highlight.js'
import 'highlight.js/styles/github-dark.css'

const props = defineProps<{ content: string }>()

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: false,
  highlight(str, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try { return hljs.highlight(str, { language: lang }).value } catch {}
    }
    return ''
  }
})

// Runs inside the markdown-it parse pipeline (respects code spans/fences and
// emits HTML through the renderer), instead of pre-injecting KaTeX HTML into
// the raw source before parsing — which corrupted code spans (`` `$HOME` ``)
// and plain-text dollar amounts ("$50 and $60") by treating them as math
// delimiters, and then got HTML-escaped anyway because `html:false`.
//
// This package is CJS with a TS-style `export default`. Vite's dev-time esbuild
// pre-bundling doesn't unwrap that the way a plain `import x from '...'` expects
// (`x` ends up bound to the whole `{ default, __esModule }` module object, not
// the plugin function itself) — `md.use()` then throws "plugin.apply is not a
// function". Importing the namespace and unwrapping `.default` ourselves works
// under both that and Vitest's module resolution (which doesn't hit the bug).
const katexNsAny = markdownItKatexNs as unknown as { default?: (md: MarkdownIt, options?: unknown) => MarkdownIt }
const markdownItKatex = katexNsAny.default ?? (markdownItKatexNs as unknown as (md: MarkdownIt, options?: unknown) => MarkdownIt)
md.use(markdownItKatex, { throwOnError: false })

const html = computed(() => md.render(props.content || ''))
</script>

<template>
  <article class="md" v-html="html" />
</template>

<style scoped>
.md { max-width:720px; margin:0 auto; font-family:Georgia,'Charter',serif; font-size:17px; line-height:1.65; color:#e8e8ea; padding:20px 0; }
.md :deep(h1), .md :deep(h2) { border-bottom:1px solid #2a2a3a; padding-bottom:4px; }
.md :deep(pre) { background:#0b0b15; padding:12px; border-radius:6px; overflow-x:auto; }
.md :deep(blockquote) { border-left:3px solid #4dc4ff; padding-left:12px; color:#4dc4ff; font-style:italic; }
.md :deep(code):not(pre code) { background:#1a1a2a; padding:2px 5px; border-radius:3px; font-size:0.9em; }
</style>
