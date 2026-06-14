<!-- knowledge-ui/src/components/readers/ViewerToolbar.vue -->
<script setup lang="ts">
import { useI18n } from 'vue-i18n'

defineProps<{ page: number; pageCount: number; scalePct: number }>()
defineEmits<{
  (e: 'prev'): void
  (e: 'next'): void
  (e: 'zoomIn'): void
  (e: 'zoomOut'): void
  (e: 'download'): void
}>()

const { t } = useI18n()
</script>

<template>
  <div class="vt">
    <div v-if="pageCount > 1" class="vt-group" data-test="vt-pages">
      <button class="vt-btn" data-test="vt-prev" :title="t('reader.viewer.prevPage')" @click="$emit('prev')">‹</button>
      <span class="vt-pageno">{{ page }} / {{ pageCount }}</span>
      <button class="vt-btn" data-test="vt-next" :title="t('reader.viewer.nextPage')" @click="$emit('next')">›</button>
    </div>

    <div class="vt-group" data-test="vt-zoom">
      <button class="vt-btn" data-test="vt-zoom-out" :title="t('reader.viewer.zoomOut')" @click="$emit('zoomOut')">−</button>
      <span class="vt-pct">{{ scalePct }}%</span>
      <button class="vt-btn" data-test="vt-zoom-in" :title="t('reader.viewer.zoomIn')" @click="$emit('zoomIn')">+</button>
    </div>

    <button class="vt-btn vt-dl" data-test="vt-download" :title="t('reader.viewer.download')" @click="$emit('download')">⬇</button>
  </div>
</template>

<style scoped>
.vt {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 6px 10px;
  background: var(--bg-1, #16161e);
  border-bottom: 1px solid var(--line, #2a2a3a);
  color: var(--text-1, #eee);
  font-size: 13px;
}
.vt-group { display: flex; align-items: center; gap: 6px; }
.vt-btn {
  min-width: 44px; min-height: 44px;
  display: inline-flex; align-items: center; justify-content: center;
  background: none; border: none; color: inherit; cursor: pointer;
  font-size: 18px; border-radius: 8px;
}
.vt-btn:hover { background: var(--bg-3, rgba(255,255,255,.08)); }
.vt-pageno, .vt-pct { min-width: 56px; text-align: center; font-variant-numeric: tabular-nums; }
.vt-dl { margin-left: auto; }
@media (max-width: 700px) {
  .vt { gap: 8px; padding: 4px 6px; }
  .vt-pageno, .vt-pct { min-width: 48px; font-size: 12px; }
}
</style>
