<script setup lang="ts">
import { ref, computed } from 'vue'
import { useGestureZoom } from '../../composables/useGestureZoom'
import ViewerToolbar from './ViewerToolbar.vue'

const props = defineProps<{ url: string; kind: 'pdf' | 'image'; filename?: string }>()

const z = useGestureZoom({ minScale: 1, maxScale: 6, doubleTapScale: 2.5 })
const scalePct = computed(() => Math.round(z.scale.value * 100))

const error = ref(false)
const page = ref(1)
const pageCount = ref(1) // images are always single-page; pdf sets this in a later task

// ── pointer gestures ──────────────────────────────────────────────────────────
const pointers = new Map<number, { x: number; y: number }>()
let startDist = 0
let startScale = 1
let lastTapTs = 0

function dist(a: { x: number; y: number }, b: { x: number; y: number }) {
  return Math.hypot(a.x - b.x, a.y - b.y)
}
function onPointerDown(e: PointerEvent) {
  ;(e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId)
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  if (pointers.size === 2) {
    const [a, b] = [...pointers.values()]
    startDist = dist(a, b)
    startScale = z.scale.value
  }
}
function onPointerMove(e: PointerEvent) {
  const prev = pointers.get(e.pointerId)
  if (!prev) return
  const cur = { x: e.clientX, y: e.clientY }
  pointers.set(e.pointerId, cur)
  if (pointers.size === 2 && startDist > 0) {
    const [a, b] = [...pointers.values()]
    z.setScale(startScale * (dist(a, b) / startDist))
  } else if (pointers.size === 1) {
    z.panBy(cur.x - prev.x, cur.y - prev.y)
  }
}
function onPointerUp(e: PointerEvent) {
  pointers.delete(e.pointerId)
  if (pointers.size < 2) startDist = 0
  // double-tap detection
  const now = Date.now()
  if (now - lastTapTs < 300) { z.toggleZoom(); lastTapTs = 0 } else { lastTapTs = now }
}
function onWheel(e: WheelEvent) {
  if (!e.ctrlKey) return
  e.preventDefault()
  z.zoomBy(e.deltaY < 0 ? 1.1 : 0.9)
}
function onDblClick() { z.toggleZoom() }

function download() {
  const a = document.createElement('a')
  a.href = props.url
  a.download = props.filename || ''
  document.body.appendChild(a)
  a.click()
  a.remove()
}

// page nav (wired for pdf in a later task; no-ops for images)
function prev() { if (page.value > 1) { page.value--; z.reset() } }
function next() { if (page.value < pageCount.value) { page.value++; z.reset() } }
</script>

<template>
  <div class="dv">
    <ViewerToolbar
      :page="page" :pageCount="pageCount" :scalePct="scalePct"
      @prev="prev" @next="next"
      @zoomIn="z.zoomBy(1.25)" @zoomOut="z.zoomBy(0.8)"
      @download="download"
    />

    <div
      class="dv-surface"
      @pointerdown="onPointerDown" @pointermove="onPointerMove"
      @pointerup="onPointerUp" @pointercancel="onPointerUp"
      @wheel="onWheel" @dblclick="onDblClick"
    >
      <div v-if="error" class="dv-error" data-test="dv-error">
        <p>{{ $t('reader.viewer.loadError') }}</p>
        <a :href="url" :download="filename || ''">{{ $t('reader.viewer.download') }}</a>
      </div>

      <img
        v-else-if="kind === 'image'"
        data-test="dv-image"
        class="dv-content"
        :src="url"
        :style="{ transform: z.transform.value }"
        :alt="filename || ''"
        @error="error = true"
      >
      <!-- pdf canvas added in a later task -->
    </div>
  </div>
</template>

<style scoped>
.dv { position: absolute; inset: 0; display: flex; flex-direction: column; background: var(--bg-0, #0a0a14); }
.dv-surface {
  flex: 1; position: relative; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
  touch-action: none; /* we handle gestures ourselves */
}
.dv-content { max-width: 100%; max-height: 100%; transform-origin: center center; will-change: transform; }
.dv-error { color: var(--text-2, #aaa); text-align: center; padding: 40px; }
.dv-error a { color: var(--cyber, #8ab4f8); display: inline-block; margin-top: 10px; }
</style>
