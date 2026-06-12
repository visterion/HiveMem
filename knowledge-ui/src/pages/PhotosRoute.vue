<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useMediaStore } from '../stores/media'
import { useJustifiedRows } from '../composables/justified'
import PhotoTile from '../components/media/PhotoTile.vue'
import Lightbox from '../components/media/Lightbox.vue'
import type { MediaItem } from '../api/types'

const { t, locale } = useI18n()
const media = useMediaStore()

const stageEl = ref<HTMLElement | null>(null)
const containerWidth = ref(0)
let ro: ResizeObserver | null = null

function measure() {
  if (stageEl.value) containerWidth.value = Math.round(stageEl.value.getBoundingClientRect().width)
}

onMounted(() => {
  void media.load()
  measure()
  if (typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(() => measure())
    if (stageEl.value) ro.observe(stageEl.value)
  }
})
onBeforeUnmount(() => { ro?.disconnect(); ro = null })

function labelFor(key: string): string {
  if (key === 'today') return t('photos.today')
  if (key === 'week') return t('photos.thisWeek')
  if (key === 'month') return t('photos.thisMonth')
  // 'YYYY-MM' → localized "Month YYYY"
  const m = key.match(/^(\d{4})-(\d{2})$/)
  if (m) return new Date(Number(m[1]), Number(m[2]) - 1, 1).toLocaleDateString(locale.value, { month: 'long', year: 'numeric' })
  return key
}

function rowsFor(items: MediaItem[]) {
  return useJustifiedRows(items, containerWidth.value || 800, 132, 4)
}
const isEmpty = computed(() => media.loaded && media.photos.length === 0 && !media.error)
</script>

<template>
  <div class="page" ref="stageEl">
    <div class="photos-wrap">
      <div class="photos-head">
        <div>
          <div class="h-eyebrow">{{ t('nav.photos') }}</div>
          <h1 class="h-display" style="font-size:26px;margin:4px 0 0">{{ t('photos.sub') }}</h1>
        </div>
      </div>

      <div v-if="media.error" class="notice">{{ t('photos.loadError') }}</div>
      <div v-else-if="isEmpty" class="empty">{{ t('photos.empty') }}</div>

      <div v-for="group in media.groups" :key="group.key" class="photo-group">
        <div class="photo-date">{{ labelFor(group.key) }}</div>
        <div class="photo-rows">
          <div v-for="(row, ri) in rowsFor(group.items)" :key="ri" class="photo-row" :style="{ height: row.height + 'px' }">
            <div v-for="cell in row.items" :key="cell.item.cell_id" class="photo-slot"
                 :style="{ width: cell.width + 'px', height: cell.height + 'px' }">
              <PhotoTile :item="cell.item"
                         @open="media.openLightbox(media.photos.indexOf(cell.item))" />
            </div>
          </div>
        </div>
      </div>
    </div>

    <Lightbox v-if="media.lightboxItem" :item="media.lightboxItem"
              @close="media.closeLightbox()" @next="media.next()" @prev="media.prev()" />
  </div>
</template>

<style scoped>
.page { flex:1; overflow-y:auto; min-height:0; height:100%; }
.photos-wrap { padding:26px 30px 60px; }
.photos-head { display:flex; align-items:flex-end; justify-content:space-between; margin:0 4px 18px; }
.photo-group { margin-bottom:30px; }
.photo-date { font-family:var(--font-display); font-size:15px; font-weight:600; color:var(--text-1); margin:0 4px 10px; }
.photo-rows { display:flex; flex-direction:column; gap:4px; }
.photo-row { display:flex; gap:4px; }
.photo-slot { flex:none; }
.empty, .notice { display:grid; place-items:center; min-height:200px; color:var(--text-2); }
.h-display { font-family:var(--font-display); font-weight:600; letter-spacing:-0.02em; color:var(--text-0); }
</style>
