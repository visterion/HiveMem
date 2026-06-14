<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useGestureZoom } from '../../composables/useGestureZoom'
import ViewerToolbar from './ViewerToolbar.vue'

const props = defineProps<{ url: string; kind: 'pdf' | 'image'; filename?: string }>()

const z = useGestureZoom({ minScale: 1, maxScale: 6, doubleTapScale: 2.5 })
const scalePct = computed(() => Math.round(z.scale.value * 100))

const error = ref(false)
const page = ref(1)
const pageCount = ref(1) // images are always single-page; pdf sets this on load

const canvas = ref<HTMLCanvasElement>()
const surface = ref<HTMLElement>()
let pdfDoc: any = null

async function loadPdf() {
  try {
    const pdfjs: any = await import('pdfjs-dist')
    pdfjs.GlobalWorkerOptions.workerSrc =
      new URL('pdfjs-dist/build/pdf.worker.min.mjs', import.meta.url).toString()
    pdfDoc = await pdfjs.getDocument(props.url).promise
    pageCount.value = pdfDoc.numPages
    await renderPage(1)
  } catch {
    error.value = true
  }
}

async function renderPage(n: number) {
  if (!pdfDoc || !canvas.value) return
  try {
    const pdfPage = await pdfDoc.getPage(n)
    const dpr = window.devicePixelRatio || 1
    const base = pdfPage.getViewport({ scale: 1 })
    const fitW = (surface.value?.clientWidth || base.width)
    const fit = Math.max(0.1, fitW / base.width)
    const vp = pdfPage.getViewport({ scale: fit * dpr })
    const cv = canvas.value
    cv.width = Math.floor(vp.width)
    cv.height = Math.floor(vp.height)
    cv.style.width = Math.floor(vp.width / dpr) + 'px'
    cv.style.height = Math.floor(vp.height / dpr) + 'px'
    const ctx = cv.getContext('2d')!
    await pdfPage.render({ canvasContext: ctx, viewport: vp }).promise
  } catch {
    error.value = true
  }
}

onMounted(() => { if (props.kind === 'pdf') loadPdf() })
onBeforeUnmount(() => { pdfDoc?.cleanup?.(); pdfDoc?.destroy?.() })

// ── pointer gestures ──────────────────────────────────────────────────────────
const pointers = new Map<number, { x: number; y: number }>()
let startDist = 0
let startScale = 1
let lastTapTs = 0
let wasPinching = false

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
    wasPinching = true
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
  // Only a clean single-finger lift counts as a tap. Lifting fingers from a pinch
  // would otherwise be misread as a double-tap (two quick pointerups), so a pinch
  // cancels tap tracking entirely.
  if (pointers.size !== 0) return
  if (wasPinching) { wasPinching = false; lastTapTs = 0; return }
  const now = Date.now()
  if (now - lastTapTs < 300) { z.toggleZoom(); lastTapTs = 0 } else { lastTapTs = now }
}
function onPointerCancel(e: PointerEvent) {
  pointers.delete(e.pointerId)
  if (pointers.size < 2) startDist = 0
  // A cancelled pointer (palm rejection, OS interruption) is not a tap.
  wasPinching = false
  lastTapTs = 0
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

// page nav
function prev() { if (page.value > 1) page.value-- }
function next() { if (page.value < pageCount.value) page.value++ }
watch(page, n => { z.reset(); if (props.kind === 'pdf') renderPage(n) })
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
      ref="surface"
      class="dv-surface"
      @pointerdown="onPointerDown" @pointermove="onPointerMove"
      @pointerup="onPointerUp" @pointercancel="onPointerCancel"
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
      <canvas
        v-else-if="kind === 'pdf'"
        ref="canvas"
        data-test="dv-canvas"
        class="dv-content"
        :style="{ transform: z.transform.value }"
      ></canvas>
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
