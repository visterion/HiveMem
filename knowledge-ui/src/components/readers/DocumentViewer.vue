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

// GPU-safe maximum texture dimension; bitmaps never exceed this on the long side.
const MAX_CANVAS_DIM = 4096
// Zoom factor the current PDF bitmap was rasterized at. Live pinch/zoom only
// changes the CSS transform (smooth, GPU); once the zoom settles we re-rasterize
// the page at this scale so the document stays crisp instead of CSS-upscaled.
const renderScale = ref(1)

// Images carry the full zoom in the CSS transform. The PDF canvas is rasterized
// at renderScale, so its transform only needs the residual zoom (scale / renderScale).
const canvasTransform = computed(
  () => `translate(${z.tx.value}px, ${z.ty.value}px) scale(${z.scale.value / renderScale.value})`,
)

const canvas = ref<HTMLCanvasElement>()
const surface = ref<HTMLElement>()
let pdfDoc: any = null
let renderTask: { promise: Promise<void>; cancel?: () => void } | null = null
let zoomTimer: ReturnType<typeof setTimeout> | null = null

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
  // Cancel an in-flight render so rapid page changes can't paint out of order.
  renderTask?.cancel?.()
  renderTask = null
  if (!pdfDoc || !canvas.value) return
  try {
    const pdfPage = await pdfDoc.getPage(n)
    const dpr = window.devicePixelRatio || 1
    const base = pdfPage.getViewport({ scale: 1 })
    const fitW = (surface.value?.clientWidth || base.width)
    const fit = Math.max(0.1, fitW / base.width)
    // CSS size: page fits the surface width at zoom 1 and grows with renderScale.
    const cssScale = fit * renderScale.value
    // Device size: oversample by the device pixel ratio and the current zoom so a
    // zoomed-in page is rasterized at its real resolution rather than stretched.
    // Clamp the long side to a GPU-safe texture dimension.
    let devScale = cssScale * dpr
    const longest = Math.max(base.width, base.height)
    if (longest * devScale > MAX_CANVAS_DIM) devScale = MAX_CANVAS_DIM / longest
    const cv = canvas.value
    const devVp = pdfPage.getViewport({ scale: devScale })
    const cssVp = pdfPage.getViewport({ scale: cssScale })
    cv.width = Math.floor(devVp.width)
    cv.height = Math.floor(devVp.height)
    cv.style.width = Math.floor(cssVp.width) + 'px'
    cv.style.height = Math.floor(cssVp.height) + 'px'
    const ctx = cv.getContext('2d')
    if (!ctx) { error.value = true; return }
    const task = pdfPage.render({ canvasContext: ctx, viewport: devVp })
    renderTask = task
    await task.promise
    renderTask = null
  } catch (e: any) {
    // A cancelled render is expected when the page changes mid-flight; ignore it.
    if (e?.name !== 'RenderingCancelledException') error.value = true
  }
}

// Reload when the attachment changes (the reader reuses one viewer instance
// across attachment tabs, so url/kind can change without a remount).
watch(() => `${props.kind}|${props.url}`, () => {
  pdfDoc?.destroy?.()
  pdfDoc = null
  error.value = false
  page.value = 1
  pageCount.value = 1 // re-set by loadPdf for PDFs; stays 1 for images
  renderScale.value = 1
  if (zoomTimer) { clearTimeout(zoomTimer); zoomTimer = null }
  z.reset()
  if (props.kind === 'pdf') loadPdf()
})

// Re-rasterize the PDF at the new zoom once the gesture settles, so deep zoom
// stays sharp. Debounced to avoid thrashing during a continuous pinch.
watch(() => z.scale.value, () => {
  if (props.kind !== 'pdf') return
  if (zoomTimer) clearTimeout(zoomTimer)
  zoomTimer = setTimeout(() => {
    zoomTimer = null
    const target = Math.min(z.maxScale, Math.max(1, z.scale.value))
    if (Math.abs(target - renderScale.value) < 0.1) return
    renderScale.value = target
    renderPage(page.value)
  }, 180)
})

onMounted(() => { if (props.kind === 'pdf') loadPdf() })
onBeforeUnmount(() => {
  if (zoomTimer) clearTimeout(zoomTimer)
  renderTask?.cancel?.(); pdfDoc?.cleanup?.(); pdfDoc?.destroy?.()
})

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
watch(page, n => {
  z.reset()
  renderScale.value = 1
  if (zoomTimer) { clearTimeout(zoomTimer); zoomTimer = null }
  if (props.kind === 'pdf') renderPage(n)
})
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
        class="dv-content dv-image"
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
        :style="{ transform: canvasTransform }"
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
/* Shared: the zoom transform pivots around the element centre. */
.dv-content { transform-origin: center center; will-change: transform; }
/* Images fit the surface at zoom 1; the zoom transform grows them past it. */
.dv-image { max-width: 100%; max-height: 100%; }
/* The PDF canvas sizes itself explicitly (re-rasterised per zoom level), so it
   must NOT be clamped to the surface — once zoomed in its CSS box exceeds it. */
.dv-error { color: var(--text-2, #aaa); text-align: center; padding: 40px; }
.dv-error a { color: var(--cyber, #8ab4f8); display: inline-block; margin-top: 10px; }
</style>
