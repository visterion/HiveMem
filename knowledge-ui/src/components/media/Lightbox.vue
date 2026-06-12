<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import type { MediaItem } from '../../api/types'
import { useCanvasStore } from '../../stores/canvas'
import HmIcon from '../shell/HmIcon.vue'

const props = defineProps<{ item: MediaItem }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'next'): void; (e: 'prev'): void }>()

const { t } = useI18n()
const router = useRouter()
const canvas = useCanvasStore()

const DASH = '—'
const contentUrl = computed(() =>
  props.item.attachment_id ? `/api/attachments/${props.item.attachment_id}/content` : null)
const camera = computed(() => {
  const parts = [props.item.camera_make, props.item.camera_model].filter(Boolean)
  return parts.length ? parts.join(' ') : DASH
})
const resolution = computed(() =>
  props.item.width && props.item.height ? `${props.item.width} × ${props.item.height}` : DASH)
const hasGps = computed(() => props.item.gps_lat != null && props.item.gps_lon != null)
const osmHref = computed(() => hasGps.value
  ? `https://www.openstreetmap.org/?mlat=${props.item.gps_lat}&mlon=${props.item.gps_lon}#map=15/${props.item.gps_lat}/${props.item.gps_lon}`
  : null)
const place = computed(() => props.item.place_name ?? DASH)
const sizeText = computed(() => props.item.size_bytes
  ? `${(props.item.size_bytes / 1_048_576).toFixed(1)} MB` : DASH)
const dateText = computed(() => {
  const iso = props.item.taken_at ?? props.item.created_at
  return iso ? new Date(iso).toLocaleDateString() : DASH
})

function showInGraph() {
  canvas.setFocus(props.item.cell_id)
  router.push({ name: 'graph' })
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
  else if (e.key === 'ArrowRight') emit('next')
  else if (e.key === 'ArrowLeft') emit('prev')
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div class="lightbox" @click="emit('close')">
    <button class="lb-close iconbtn" data-testid="lb-close" @click.stop="emit('close')">
      <HmIcon name="close" />
    </button>
    <button class="lb-nav lb-prev" data-testid="lb-prev" @click.stop="emit('prev')">‹</button>
    <button class="lb-nav lb-next" data-testid="lb-next" @click.stop="emit('next')">›</button>
    <div class="lb-stage" @click.stop>
      <div class="lb-img">
        <img v-if="contentUrl" :src="contentUrl" alt="" />
      </div>
      <div class="lb-meta">
        <div class="h-display" style="font-size:18px">{{ item.summary || item.cell_id }}</div>
        <div class="section-label">{{ t('photos.exif') }}</div>
        <div class="meta-grid">
          <div><div class="k">{{ t('photos.camera') }}</div><div class="v">{{ camera }}</div></div>
          <div><div class="k">{{ t('photos.resolution') }}</div><div class="v">{{ resolution }}</div></div>
          <div><div class="k">{{ t('photos.location') }}</div><div class="v">
            <a v-if="osmHref" class="osm-link" :href="osmHref" target="_blank" rel="noopener">{{ place }}</a>
            <template v-else>{{ place }}</template>
          </div></div>
          <div><div class="k">{{ t('photos.size') }}</div><div class="v">{{ sizeText }}</div></div>
          <div><div class="k">{{ t('photos.takenAt') }}</div><div class="v">{{ dateText }}</div></div>
        </div>
        <button class="btn ghost" data-testid="lb-graph" style="margin-top:20px" @click="showInGraph">
          <HmIcon name="graph" :size="16" /> {{ t('photos.showInGraph') }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.lightbox { position:fixed; inset:0; z-index:200; background:rgba(5,6,9,0.86); backdrop-filter:blur(14px);
  display:flex; align-items:center; justify-content:center; padding:40px; animation:fadeIn .2s; }
.lb-close { position:absolute; top:22px; right:24px; color:#fff; width:40px; height:40px; }
.lb-nav { position:absolute; top:50%; transform:translateY(-50%); background:rgba(255,255,255,0.08);
  color:#fff; border:none; width:46px; height:64px; font-size:34px; cursor:pointer; border-radius:10px; }
.lb-prev { left:24px; } .lb-next { right:24px; }
.lb-nav:hover { background:rgba(255,255,255,0.16); }
.lb-stage { display:flex; gap:0; max-width:1100px; width:100%; max-height:80vh; border-radius:16px;
  overflow:hidden; box-shadow:var(--shadow-2); background:var(--bg-2); border:1px solid var(--line-2); }
.lb-img { flex:1.6; min-height:460px; background:#000; display:grid; place-items:center; }
.lb-img img { width:100%; height:100%; object-fit:contain; max-height:80vh; }
.lb-meta { flex:1; padding:26px; max-width:320px; }
.osm-link { color:var(--honey); text-decoration:none; }
.osm-link:hover { text-decoration:underline; }
</style>
