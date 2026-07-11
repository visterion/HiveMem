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
// This package is CJS with a TS-style `export default`, and the interop wrapping
// differs by toolchain — sometimes single (`{ default: fn }`), sometimes double
// (Vite's dev-time esbuild prebundling emits `export default require()`, so the
// namespace is `{ default: { default: fn } }`). A single `.default` unwrap left
// an object, and `md.use()` threw "plugin.apply is not a function". Peel
// `.default` until we actually reach the plugin function — robust across Vite
// dev prebundling, the production build, and Vitest.
function resolvePlugin(mod: unknown): (md: MarkdownIt, options?: unknown) => MarkdownIt {
  let cur: unknown = mod
  while (cur && typeof cur !== 'function' && typeof (cur as { default?: unknown }).default !== 'undefined') {
    cur = (cur as { default?: unknown }).default
  }
  return cur as (md: MarkdownIt, options?: unknown) => MarkdownIt
}
md.use(resolvePlugin(markdownItKatexNs), { throwOnError: false })

// DocInfoTab rewrites [page=N] OCR markers into markdown `---` thematic breaks
// before handing us the content (see textContent there). Tag the rendered <hr>
// with a page-separator class/data-test hook so it reads as a page break instead
// of a generic rule, without needing `html:true` (which would reopen the
// code-span/dollar-amount corruption this component's KaTeX handling works
// around — see the comment above).
md.renderer.rules.hr = () => '<hr class="page-sep" data-test="page-sep">\n'

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
.md :deep(hr.page-sep) { border:none; border-top:1px dashed #2a2a3a; margin:22px 0 8px; }
</style>
