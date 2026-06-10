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

const el = ref<HTMLElement | null>(null)
const canvas = useCanvasStore()
const cell = useCellStore()
const graph = computed(() => mapCanvasToForceGraph({ cells: canvas.cells, tunnels: canvas.tunnels }))
const clusterCenters = computed(() => realmClusterCenters(canvas.cells.map(c => c.realm)))
const size = ref({ width: 0, height: 0 })

let root: Root | null = null
let resizeObserver: ResizeObserver | null = null
let zoomTimer: ReturnType<typeof setTimeout> | null = null
const fgRef: { current: ForceGraphMethods<GraphNode, GraphLink> | undefined } = { current: undefined }

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
    nodes: graph.value.nodes,
    links: graph.value.links,
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
  fg.d3Force('x', forceX((n: unknown) => centers[(n as GraphNode).realm]?.x ?? 0).strength(0.22))
  fg.d3Force('y', forceY((n: unknown) => centers[(n as GraphNode).realm]?.y ?? 0).strength(0.22))
  fg.d3ReheatSimulation()
  // Frame the whole constellation once it settles (guarded for the mocked-root test env).
  // Track the timer so a quick unmount/remount can't fire a stale zoomToFit on a new instance.
  if (zoomTimer) clearTimeout(zoomTimer)
  zoomTimer = setTimeout(() => fgRef.current?.zoomToFit?.(500, 70), 1400)
}

onMounted(() => {
  if (!el.value) return
  root = createRoot(el.value)
  updateSize()
  resizeObserver = new ResizeObserver(entries => updateSize(entries[0]?.contentRect))
  resizeObserver.observe(el.value)
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
