<script setup lang="ts">
import { useI18n } from 'vue-i18n'
const props = defineProps<{ scores: Record<string, number> }>()
const { t } = useI18n()
const KEYS = ['semantic','keyword','recency','importance','popularity','graph_proximity'] as const
const rows = () => KEYS.map(k => ({ k, label: t('inspector.sig_' + k), val: props.scores['score_' + k] ?? 0 }))
</script>
<template>
  <div class="signals">
    <div v-for="s in rows()" :key="s.k" class="sig">
      <span class="lbl">{{ s.label }}</span>
      <span class="track"><span class="fill" :style="{ width: Math.round(s.val*100) + '%' }" /></span>
      <span class="val">{{ s.val.toFixed(2) }}</span>
    </div>
  </div>
</template>
<style scoped>
.signals { display:flex; flex-direction:column; gap:7px; }
.sig { display:grid; grid-template-columns:92px 1fr 30px; align-items:center; gap:10px; }
.sig .lbl { font-size:11.5px; color:var(--text-1); }
.sig .track { height:6px; border-radius:4px; background:var(--bg-4); overflow:hidden; }
.sig .fill { height:100%; border-radius:4px; background:linear-gradient(90deg,var(--honey-deep),var(--honey-bright)); }
.sig .val { font-family:var(--font-mono); font-size:11px; color:var(--text-2); text-align:right; }
</style>
