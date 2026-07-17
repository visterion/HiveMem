<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { forceX, forceY } from 'd3-force'
import type { ForceGraphMethods } from 'react-force-graph-2d'
import { useCanvasStore } from '../../stores/canvas'
import { useCellStore } from '../../stores/cell'
import { mapCanvasToForceGraph } from '../../graph/mapCanvasToForceGraph'
import { realmClusterCenters } from '../../graph/clusterForce'
import type { GraphLink, GraphNode } from '../../graph/types'
import { ForceGraphRoot } from './ForceGraphRoot'
import { NO_REALM } from '../../composables/realmMeta'

const el = ref<HTMLElement | null>(null)
const canvas = useCanvasStore()
const cell = useCellStore()
const graph = computed(() => mapCanvasToForceGraph({ cells: canvas.cells, tunnels: canvas.tunnels }))
const clusterCenters = computed(() => realmClusterCenters(canvas.cells.map(c => c.realm ?? NO_REALM)))
const size = ref({ width: 0, height: 0 })

let root: Root | null = null
let resizeObserver: ResizeObserver | null = null
let zoomTimer: ReturnType<typeof setTimeout> | null = null
const fgRef: { current: ForceGraphMethods<GraphNode, GraphLink> | undefined } = { current: undefined }

// Root cause of the prod "labels vanish on zoom-in" symptom: it wasn't the label-zoom math
// (shouldShowLabel already shows everything once globalScale crosses LABEL_ZOOM — verified by
// reproduction). It was this component's own deferred zoomToFit: every reheat (initial mount,
// or a new batch from the progressive cell/tunnel stream) schedules a zoomToFit 1400ms later.
// If the user zooms in during that window, the deferred call fires anyway and snaps the camera
// back out to fit the whole graph — landing well below LABEL_ZOOM, so every label disappears at
// once. Reproduced via Playwright: zoomed to k=3.5 (labels visible), then ~1.4s later the pending
// zoomToFit fired and reset k to 0.40 (labels gone) with no further input. Fix: track genuine
// user gestures (wheel/pointerdown on the canvas) and skip the deferred auto-fit once the user
// has taken the wheel.
let userInteracted = false
function markUserInteracted() {
  userInteracted = true
}

function updateSize(measurement?: { width: number; height: number }) {
  if (!el.value && !measurement) return
  const { width, height } = measurement ?? el.value!.getBoundingClientRect()
  size.value = { width: Math.round(width), height: Math.round(height) }
}

async function focusLoadedCell(id: string) {
  const previousFocus = canvas.focusedId
  canvas.setHover(null)
  try {
    const result = cell.load(id)
    if (result && typeof (result as PromiseLike<unknown>).then === 'function') {
      await result
    }
    canvas.setFocus(id)
  } catch {
    canvas.setFocus(previousFocus)
    canvas.setHover(null)
  }
}

function renderReact() {
  if (!root) return
  root.render(createElement(ForceGraphRoot, {
    graph: graph.value,
    width: size.value.width,
    height: size.value.height,
    focusedId: canvas.focusedId,
    hoveredId: canvas.hoveredId,
    forceGraphRef: fgRef,
    onNodeHover: id => canvas.setHover(id),
    onNodeClick: id => {
      void focusLoadedCell(id)
    }
  }))
}

// Install per-realm cluster forces on the live ForceGraph2D instance. Runs only on
// data/cluster changes (NOT on hover/focus) so the layout doesn't jitter; guarded so
// it no-ops until the React instance has mounted (e.g. under the test's mocked root).
function installClusterForces() {
  const fg = fgRef.current
  if (!fg) return
  const centers = clusterCenters.value
  fg.d3Force('charge')?.strength(-120)
  fg.d3Force('link')?.distance(38)
  fg.d3Force('x', forceX((n: unknown) => centers[(n as GraphNode).realm ?? NO_REALM]?.x ?? 0).strength(0.22))
  fg.d3Force('y', forceY((n: unknown) => centers[(n as GraphNode).realm ?? NO_REALM]?.y ?? 0).strength(0.22))
  fg.d3ReheatSimulation()
  // Frame the whole constellation once it settles (guarded for the mocked-root test env).
  // Track the timer so a quick unmount/remount can't fire a stale zoomToFit on a new instance.
  if (zoomTimer) clearTimeout(zoomTimer)
  zoomTimer = setTimeout(() => {
    if (!userInteracted) fgRef.current?.zoomToFit?.(500, 70)
  }, 1400)
}

onMounted(() => {
  if (!el.value) return
  root = createRoot(el.value)
  updateSize()
  resizeObserver = new ResizeObserver(entries => updateSize(entries[0]?.contentRect))
  resizeObserver.observe(el.value)
  // Track genuine user zoom/pan gestures so the deferred auto zoomToFit (installClusterForces)
  // never overrides them — see the root-cause comment on `userInteracted` above. MUST be
  // registered with `capture: true`: d3-zoom's own wheel/pointerdown handlers on the canvas
  // call event.stopImmediatePropagation() (d3-zoom's `nopropagation`/`noevent` helpers), which
  // kills bubble-phase listeners on ancestors before they ever run. A capture-phase listener
  // on this wrapper fires on the way down, before the event reaches the canvas, so it isn't
  // affected by that later stopImmediatePropagation() call.
  el.value.addEventListener('wheel', markUserInteracted, { passive: true, capture: true })
  el.value.addEventListener('pointerdown', markUserInteracted, { capture: true })
  renderReact()
  queueMicrotask(installClusterForces)
})

// Data (and therefore cluster) changes: re-render AND reinstall forces (react-force-graph
// resets forces to defaults when graphData changes).
watch(graph, () => {
  renderReact()
  queueMicrotask(installClusterForces)
})

// Pure visual changes: re-render only, no force reinstall.
watch([size, () => canvas.focusedId, () => canvas.hoveredId], renderReact)

onBeforeUnmount(() => {
  if (zoomTimer) { clearTimeout(zoomTimer); zoomTimer = null }
  resizeObserver?.disconnect()
  resizeObserver = null
  el.value?.removeEventListener('wheel', markUserInteracted, { capture: true })
  el.value?.removeEventListener('pointerdown', markUserInteracted, { capture: true })
  root?.unmount()
  root = null
  fgRef.current = undefined
})
</script>

<template>
  <div ref="el" data-testid="force-graph-bridge" class="graph-bridge" />
</template>

<style scoped>
.graph-bridge {
  width: 100%;
  height: 100%;
}
</style>
