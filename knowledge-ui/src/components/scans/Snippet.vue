<script setup lang="ts">
import { computed } from 'vue'
import HmIcon from '../shell/HmIcon.vue'
const props = defineProps<{ text?: string; q?: string }>()
const seg = computed(() => {
  const q = (props.q || '').trim(); if (!q) return null
  const flat = (props.text || '').replace(/\[page=\d+\]/g, ' ').replace(/\s+/g, ' ').trim()
  const i = flat.toLowerCase().indexOf(q.toLowerCase())
  if (i < 0) {
    // No literal match (e.g. semantic-only hit) — still show a snippet so search
    // cards aren't blank; take the head of the cleaned text without a highlight.
    if (!flat) return null
    const head = flat.slice(0, 140)
    return { pre: '', hit: '', post: head + (flat.length > 140 ? '…' : '') }
  }
  const start = Math.max(0, i - 32)
  const s = flat.slice(start, i + q.length + 60)
  const lo = s.toLowerCase().indexOf(q.toLowerCase())
  return { pre: (start > 0 ? '…' : '') + s.slice(0, lo), hit: s.slice(lo, lo + q.length), post: s.slice(lo + q.length) + '…' }
})
</script>
<template>
  <div v-if="seg" class="ocr-snip">
    <HmIcon name="search" :size="12" />
    <span>{{ seg.pre }}<mark>{{ seg.hit }}</mark>{{ seg.post }}</span>
  </div>
</template>
<style scoped>
.ocr-snip { display:flex; gap:6px; align-items:flex-start; font-size:11.5px; line-height:1.45; color:var(--text-2); background:var(--bg-0); border:1px solid var(--line); border-radius:8px; padding:7px 9px; margin:8px 0; }
.ocr-snip svg { flex:none; margin-top:2px; color:var(--text-3); }
.ocr-snip mark { background:var(--honey-glow); color:var(--text-0); border-radius:3px; padding:0 2px; }
</style>
