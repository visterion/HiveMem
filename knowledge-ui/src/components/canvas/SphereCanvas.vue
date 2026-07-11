<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { Application, Container, Sprite, Graphics, Text, TextStyle } from 'pixi.js'
import { realmTexture, cellTexture, colorForRealm, parseHsl } from './textures'
import { focusFilter, hoverFilter, focusRing, godrays } from './filters'
import { spawnDust } from './particles'
import { useCanvasStore } from '../../stores/canvas'
import { useCellStore } from '../../stores/cell'
import { useReaderStore } from '../../stores/reader'
import { computeWingPositions, poissonDiskCells } from '../../composables/layout'
import { cellVisibleAt } from '../../composables/lod'
import { computeFitView } from './fitView'
import type { Cell } from '../../api/types'

const root = ref<HTMLDivElement>()
const canvasStore = useCanvasStore()
// Guard for detached rAF loops (spawn/snap animations): once the component
// unmounts they must stop touching destroyed Pixi objects.
let alive = true
let app: Application | null = null
let world: Container | null = null
let snapToRef: ((worldX: number, worldY: number, targetZoom: number, onDone: () => void) => void) | null = null
let onCellClickRef: ((c: Cell) => void) | null = null
let cleanup: (() => void) | null = null
const renderedCellIds = new Set<string>()
const renderedRealmNames = new Set<string>()
const renderedSignalKeys = new Set<string>()
const renderedRealmLabels = new Set<string>()
const renderedSignalLabels = new Set<string>()
let edgesGraphics: Graphics | null = null
let realmLayer: Container | null = null
let signalLayer: Container | null = null
let cellLayer: Container | null = null
let labelLayer: Container | null = null
let cachedRealmPos: Map<string, { x: number; y: number }> | null = null
let cachedRealmSig: string | null = null
let cachedViewportSig: string | null = null
// Camera state, hoisted out of onMounted's closure so render() (also called
// from the `watch`s below) can drive the one-time initial zoom-to-fit
// alongside the interaction handlers set up in onMounted (wheel/pan/pinch/
// snapTo). Note: in a <script setup> SFC this top level compiles into
// setup(), so these are per-component-instance — every mount naturally
// starts at zoom=1/pan 0,0 matching its brand-new world container. The
// explicit reset at the start of onMounted is a defensive guarantee of that
// contract (e.g. should these ever move to a plain <script> module scope).
let zoom = 1, panX = 0, panY = 0
let didInitialFit = false
// True once the user changes the camera themselves (wheel/pan/pinch). The
// initial fit must never clobber a user transform — even when store data
// arrives only after the user has already started interacting.
let userInteracted = false

function applyTransform() {
  if (!world || !app) return
  world.scale.set(zoom); world.position.set(panX, panY)
  const viewLeft = -panX / zoom, viewTop = -panY / zoom
  const viewRight = viewLeft + app.screen.width / zoom
  const viewBottom = viewTop + app.screen.height / zoom
  const walk = (container: Container | null) => {
    if (!container) return
    for (const c of container.children) {
      const s = c as any
      if (s._kind === 'cell') {
        let vis = cellVisibleAt(zoom)
        if (vis) {
          const r = Math.max(s.width, s.height)
          vis = s.x + r > viewLeft && s.x - r < viewRight
             && s.y + r > viewTop  && s.y - r < viewBottom
        }
        s.visible = vis
      } else if (s._kind === 'signal' || s._kind === 'signal-label') {
        s.visible = zoom >= 0.7
      } else if (s._kind === 'realm-label') {
        s.visible = zoom <= 2.2
      }
    }
  }
  walk(realmLayer); walk(signalLayer); walk(cellLayer); walk(labelLayer)
}

onMounted(async () => {
  if (!root.value) return
  // Fresh camera per mount — must match the brand-new world container below.
  zoom = 1; panX = 0; panY = 0
  didInitialFit = false
  userInteracted = false
  app = new Application()
  await app.init({ background: 0x050510, resizeTo: root.value, antialias: true, resolution: devicePixelRatio, autoDensity: true })
  root.value.appendChild(app.canvas)
  world = new Container(); app.stage.addChild(world)
  realmLayer = new Container(); world.addChild(realmLayer)
  signalLayer = new Container(); world.addChild(signalLayer)
  edgesGraphics = new Graphics(); (edgesGraphics as any)._kind = 'edges'; world.addChild(edgesGraphics)
  cellLayer = new Container(); world.addChild(cellLayer)
  labelLayer = new Container(); world.addChild(labelLayer)

  // Add dust emitter
  const dustLayer = new Container(); app.stage.addChild(dustLayer)
  const isMobile = window.matchMedia('(max-width: 768px)').matches
  const dustUpdate = spawnDust(dustLayer, isMobile ? 120 : 280, { w: app.screen.width, h: app.screen.height })
  app.ticker.add(t => dustUpdate(t.deltaMS))

  // Add godray background
  const bg = new Graphics().rect(0, 0, 4000, 4000).fill(0x05050f)
  ;(bg as any).filters = [godrays()]
  app.stage.addChildAt(bg, 0)

  app.canvas.addEventListener('wheel', e => {
    e.preventDefault()
    const factor = Math.exp(-e.deltaY * 0.0015)
    const next = Math.min(6, Math.max(0.15, zoom * factor))
    const mouseX = e.offsetX, mouseY = e.offsetY
    panX = mouseX - (mouseX - panX) * (next / zoom)
    panY = mouseY - (mouseY - panY) * (next / zoom)
    zoom = next
    userInteracted = true
    applyTransform()
  }, { passive: false })

  const pointers = new Map<number, { x: number; y: number }>()
  let panStartX = 0, panStartY = 0, panStartPanX = 0, panStartPanY = 0
  let pinchStartDist = 0, pinchStartZoom = 1, pinchStartMidX = 0, pinchStartMidY = 0
  let pinchStartPanX = 0, pinchStartPanY = 0

  function canvasPos(e: PointerEvent) {
    const r = app!.canvas.getBoundingClientRect()
    return { x: e.clientX - r.left, y: e.clientY - r.top }
  }
  function resetGesture() {
    if (pointers.size === 1) {
      const [p] = pointers.values()
      panStartX = p.x; panStartY = p.y
      panStartPanX = panX; panStartPanY = panY
    } else if (pointers.size === 2) {
      const [a, b] = [...pointers.values()]
      pinchStartDist = Math.hypot(a.x - b.x, a.y - b.y) || 1
      pinchStartZoom = zoom
      pinchStartMidX = (a.x + b.x) / 2
      pinchStartMidY = (a.y + b.y) / 2
      pinchStartPanX = panX; pinchStartPanY = panY
    }
  }

  app.canvas.addEventListener('pointerdown', e => {
    try { app!.canvas.setPointerCapture(e.pointerId) } catch { /* synthetic events */ }
    pointers.set(e.pointerId, canvasPos(e))
    resetGesture()
  })
  const onPointerMove = (e: PointerEvent) => {
    if (!pointers.has(e.pointerId)) return
    pointers.set(e.pointerId, canvasPos(e))
    if (pointers.size === 1) {
      const [p] = pointers.values()
      panX = panStartPanX + (p.x - panStartX)
      panY = panStartPanY + (p.y - panStartY)
      userInteracted = true
      applyTransform()
    } else if (pointers.size === 2) {
      const [a, b] = [...pointers.values()]
      const dist = Math.hypot(a.x - b.x, a.y - b.y) || 1
      const next = Math.min(6, Math.max(0.15, pinchStartZoom * (dist / pinchStartDist)))
      const worldX = (pinchStartMidX - pinchStartPanX) / pinchStartZoom
      const worldY = (pinchStartMidY - pinchStartPanY) / pinchStartZoom
      const midX = (a.x + b.x) / 2, midY = (a.y + b.y) / 2
      panX = midX - worldX * next
      panY = midY - worldY * next
      zoom = next
      userInteracted = true
      applyTransform()
    }
  }
  const onPointerUp = (e: PointerEvent) => {
    pointers.delete(e.pointerId)
    resetGesture()
  }
  app.canvas.addEventListener('pointermove', onPointerMove)
  app.canvas.addEventListener('pointerup', onPointerUp)
  app.canvas.addEventListener('pointercancel', onPointerUp)
  cleanup = () => {
    app?.canvas.removeEventListener('pointermove', onPointerMove)
    app?.canvas.removeEventListener('pointerup', onPointerUp)
    app?.canvas.removeEventListener('pointercancel', onPointerUp)
  }

  function snapTo(worldX: number, worldY: number, targetZoom: number, onDone: () => void) {
    if (!app) return
    const startZoom = zoom, startPanX = panX, startPanY = panY
    const targetPanX = app.screen.width / 2 - worldX * targetZoom
    const targetPanY = app.screen.height / 2 - worldY * targetZoom
    const startT = performance.now()
    function tick(t: number) {
      if (!alive) return
      const k = Math.min(1, (t - startT) / 280)
      const e = k * k * (3 - 2 * k)
      zoom = startZoom + (targetZoom - startZoom) * e
      panX = startPanX + (targetPanX - startPanX) * e
      panY = startPanY + (targetPanY - startPanY) * e
      applyTransform()
      if (k < 1) requestAnimationFrame(tick); else onDone()
    }
    requestAnimationFrame(tick)
  }

  // Guarded load-then-focus with rollback (same pattern as ForceGraphBridge):
  // focus only moves once the cell actually loaded; a failed load restores it.
  async function onCellClick(c: Cell) {
    const previousFocus = canvasStore.focusedId
    try {
      const result = useCellStore().load(c.id)
      if (result && typeof (result as PromiseLike<unknown>).then === 'function') {
        await result
      }
      canvasStore.setFocus(c.id)
    } catch {
      canvasStore.setFocus(previousFocus)
    }
  }

  snapToRef = snapTo
  onCellClickRef = onCellClick
  render()
})

onBeforeUnmount(() => {
  alive = false
  cleanup?.(); cleanup = null
  app?.destroy(true, { children: true, texture: false }); app = null; world = null
})

function animateSpawn(sprite: any) {
  const targetScale = sprite.scale.x
  sprite.scale.set(0.01)
  sprite.alpha = 0
  const startT = performance.now()
  const tick = (t: number) => {
    if (!alive || sprite.destroyed) return
    const k = Math.min(1, (t - startT) / 520)
    const e = 1 - Math.pow(1 - k, 3)
    sprite.scale.set(targetScale * e * (1 + 0.25 * Math.sin(k * Math.PI)))
    sprite.alpha = e
    if (k < 1) requestAnimationFrame(tick)
    else { sprite.scale.set(targetScale); sprite.alpha = 1 }
  }
  requestAnimationFrame(tick)
}

const REALM_STYLE = new TextStyle({ fill: 0xffffff, fontSize: 14, fontWeight: '600', align: 'center' })
const SIGNAL_STYLE = new TextStyle({ fill: 0xdfe8ff, fontSize: 10, align: 'center' })
const RELATION_COLOR: Record<string, number> = {
  related_to: 0x9aa5ff,
  builds_on: 0x4dc4ff,
  contradicts: 0xff4d4d,
  refines: 0x4dff9c,
}

function render() {
  if (!world || !app || !realmLayer || !signalLayer || !cellLayer || !labelLayer || !edgesGraphics) return
  const width = app.screen.width, height = app.screen.height

  // 1. Group cells by (realm, signal). Deterministic layout → stable positions.
  const cellsByRealmSignal = new Map<string, Map<string, Cell[]>>()
  for (const c of canvasStore.cells) {
    if (!cellsByRealmSignal.has(c.realm)) cellsByRealmSignal.set(c.realm, new Map())
    const sm = cellsByRealmSignal.get(c.realm)!
    const sig = c.signal ?? '(none)'
    if (!sm.has(sig)) sm.set(sig, [])
    sm.get(sig)!.push(c)
  }

  // 2. Realm positions — cached; recompute only on realm-set or viewport change.
  const realmSig = canvasStore.realms.map(r => r.name).sort().join('|')
  const viewportSig = `${width}x${height}`
  let realmPos: Map<string, { x: number; y: number }>
  if (cachedRealmPos && cachedRealmSig === realmSig && cachedViewportSig === viewportSig) {
    realmPos = cachedRealmPos
  } else {
    realmPos = computeWingPositions(canvasStore.realms, canvasStore.cells, canvasStore.tunnels, { width, height })
    cachedRealmPos = realmPos; cachedRealmSig = realmSig; cachedViewportSig = viewportSig
  }
  const realmSize = new Map<string, number>()
  for (const r of canvasStore.realms) realmSize.set(r.name, 120 + Math.log(1 + r.cell_count) * 30)

  // 2b. One-time zoom-to-fit: frame the realm cluster bounds on the very first
  // layout, so mobile viewports don't open cut off. Never runs again once set,
  // and never after the user has touched the camera (data can stream in after
  // the user already panned/zoomed — their transform must not be overridden).
  if (!didInitialFit && !userInteracted && realmPos.size > 0) {
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
    for (const r of canvasStore.realms) {
      const p = realmPos.get(r.name); if (!p) continue
      const radius = (realmSize.get(r.name) ?? 120) / 2
      minX = Math.min(minX, p.x - radius); maxX = Math.max(maxX, p.x + radius)
      minY = Math.min(minY, p.y - radius); maxY = Math.max(maxY, p.y + radius)
    }
    if (minX < maxX && minY < maxY) {
      const fit = computeFitView({ minX, minY, maxX, maxY }, { w: width, h: height })
      zoom = fit.zoom; panX = fit.panX; panY = fit.panY
      applyTransform()
      didInitialFit = true
    }
  }

  // 3. Signal sub-centers + deterministic cell positions. Poisson with a fixed
  // seed gives the same first-N points regardless of how large N grows, so
  // earlier cells stay put as the signal's group fills up.
  const signalPos = new Map<string, { x: number; y: number; realm: string; name: string }>()
  const cellPos = new Map<string, { x: number; y: number }>()
  for (const r of canvasStore.realms) {
    const p = realmPos.get(r.name); if (!p) continue
    const sm = cellsByRealmSignal.get(r.name)
    // Use full signal set from realm metadata so positions exist even before the
    // first cell of a signal arrives (stable layout as streams fill in). Fall
    // back to signals derived from loaded cells when metadata is flat.
    const signalsFromMetadata = (r.signals ?? []).map(s => s.name)
    const signalsFromCells = sm ? Array.from(sm.keys()) : []
    const allSignals = Array.from(new Set([...signalsFromMetadata, ...signalsFromCells]))
        .sort((a, b) => a.localeCompare(b))
    const ringR = allSignals.length > 1 ? Math.max(36, (realmSize.get(r.name) ?? 120) * 0.22) : 0
    allSignals.forEach((sigName, i) => {
      const angle = (i / allSignals.length) * Math.PI * 2 - Math.PI / 2
      const sx = p.x + Math.cos(angle) * ringR
      const sy = p.y + Math.sin(angle) * ringR
      const key = r.name + '|' + sigName
      signalPos.set(key, { x: sx, y: sy, realm: r.name, name: sigName })
      const group = sm?.get(sigName) ?? []
      if (!group.length) return
      const pts = poissonDiskCells(group.length, { x: sx, y: sy, r: 26, minDist: 13, seed: key })
      group.forEach((c, idx) => cellPos.set(c.id, pts[idx]))
    })
  }

  // 3b. Reposition already-created sprites/labels/halos to the positions just
  // computed. Edges are rebuilt from these same maps on every render() call (step
  // 6), but sprites/labels were previously only ever positioned once, at creation
  // (steps 4/5/7b are creation-guarded by the `rendered*` sets). On a resize
  // (viewportSig change invalidates the realmPos cache) or as new cells stream in
  // and shift the deterministic layout, existing nodes stayed frozen at their old
  // coordinates while the edges snapped to the new ones — visually detaching them.
  for (const child of realmLayer.children) {
    const s = child as any
    if (s._kind !== 'realm') continue
    const p = realmPos.get(s._name)
    if (p) { s.x = p.x; s.y = p.y }
  }
  for (const child of labelLayer.children) {
    const s = child as any
    if (s._kind === 'realm-label') {
      const p = realmPos.get(s._name)
      if (p) { s.x = p.x; s.y = p.y - (realmSize.get(s._name) ?? 120) / 2 - 10 }
    } else if (s._kind === 'signal-label') {
      const sp = signalPos.get(s._key)
      if (sp) { s.x = sp.x; s.y = sp.y - 30 }
    }
  }
  for (const child of signalLayer.children) {
    const s = child as any
    if (s._kind !== 'signal') continue
    const sp = signalPos.get(s._key)
    if (sp) { s.x = sp.x; s.y = sp.y }
  }
  for (const child of cellLayer.children) {
    const s = child as any
    if (s._kind !== 'cell') continue
    const pt = cellPos.get(s._cellId)
    if (pt) { s.x = pt.x; s.y = pt.y }
  }

  // 4. Add realm sprites + labels once.
  canvasStore.realms.forEach(r => {
    const p = realmPos.get(r.name); if (!p) return
    if (!renderedRealmNames.has(r.name)) {
      const s: any = new Sprite(realmTexture(colorForRealm(r.name)))
      s.anchor.set(0.5)
      s.width = s.height = realmSize.get(r.name) ?? 140
      s.x = p.x; s.y = p.y
      s._kind = 'realm'; s._name = r.name
      realmLayer!.addChild(s)
      renderedRealmNames.add(r.name)
    }
    if (!renderedRealmLabels.has(r.name)) {
      const label: any = new Text({ text: r.name, style: REALM_STYLE })
      label.anchor.set(0.5)
      label.x = p.x
      label.y = p.y - (realmSize.get(r.name) ?? 120) / 2 - 10
      label._kind = 'realm-label'
      label._name = r.name
      labelLayer!.addChild(label)
      renderedRealmLabels.add(r.name)
    }
  })

  // 5. Signal halos + labels — added once per signal.
  signalPos.forEach((sp, key) => {
    if (!renderedSignalKeys.has(key)) {
      const halo: any = new Sprite(realmTexture(colorForRealm(sp.realm)))
      halo.anchor.set(0.5)
      halo.width = halo.height = 60
      halo.x = sp.x; halo.y = sp.y
      halo.alpha = 0.35
      halo._kind = 'signal'; halo._name = sp.name; halo._key = key
      signalLayer!.addChild(halo)
      renderedSignalKeys.add(key)
    }
    if (!renderedSignalLabels.has(key)) {
      const label: any = new Text({ text: sp.name, style: SIGNAL_STYLE })
      label.anchor.set(0.5)
      label.x = sp.x; label.y = sp.y - 30
      label._kind = 'signal-label'
      label._key = key
      label.visible = false
      labelLayer!.addChild(label)
      renderedSignalLabels.add(key)
    }
  })
  // Grow signal halos as the number of cells in them increases (cheap). Each halo
  // carries its own realm|signal key, so the count always comes from the right realm.
  for (const halo of signalLayer.children) {
    const s = halo as any
    if (s._kind !== 'signal' || typeof s._key !== 'string') continue
    const sep = s._key.lastIndexOf('|')
    const realm = s._key.slice(0, sep)
    const sig = s._key.slice(sep + 1)
    const count = cellsByRealmSignal.get(realm)?.get(sig)?.length ?? 0
    s.width = s.height = 50 + count * 6
  }

  // 6. Rebuild edges (single Graphics object — cheap enough to redraw fully).
  edgesGraphics.clear()
  for (const tu of canvasStore.tunnels) {
    const a = cellPos.get(tu.from_cell)
    const b = cellPos.get(tu.to_cell)
    if (!a || !b) continue
    const col = RELATION_COLOR[tu.relation] ?? 0x888888
    edgesGraphics.moveTo(a.x, a.y).lineTo(b.x, b.y).stroke({ width: 4, color: col, alpha: 0.22 })
    edgesGraphics.moveTo(a.x, a.y).lineTo(b.x, b.y).stroke({ width: 1.4, color: col, alpha: 0.9 })
  }

  // 7a. Remove sprites for cells that no longer exist in the store (reset,
  // deletion, replacement) so no ghost/clickable dead sprites linger.
  const liveIds = new Set(canvasStore.cells.map(c => c.id))
  for (const child of [...cellLayer.children]) {
    const s = child as any
    if (s._kind !== 'cell' || liveIds.has(s._cellId)) continue
    cellLayer.removeChild(s)
    s.destroy()
    renderedCellIds.delete(s._cellId)
  }

  // 7b. Add new cell sprites only (existing cells keep their sprite instances).
  for (const c of canvasStore.cells) {
    if (renderedCellIds.has(c.id)) continue
    const pt = cellPos.get(c.id); if (!pt) continue
    const ds: any = new Sprite(cellTexture())
    ds.anchor.set(0.5)
    ds.width = ds.height = 14 + c.importance * 4
    ds.tint = parseHsl(colorForRealm(c.realm))
    ds.x = pt.x; ds.y = pt.y
    ds._kind = 'cell'; ds._cellId = c.id
    ds.eventMode = 'static'
    ds.cursor = 'pointer'
    animateSpawn(ds)
    let lastClick = 0
    ds.on('pointertap', () => {
      const now = performance.now()
      if (now - lastClick < 320) {
        useReaderStore().openReader(c.id)
      } else {
        snapToRef?.(pt.x, pt.y, 2.2, () => onCellClickRef?.(c))
      }
      lastClick = now
    })
    ds.on('pointerover', () => { ds.filters = [hoverFilter()] })
    ds.on('pointerout', () => {
      ds.filters = canvasStore.focusedId === c.id ? [focusFilter(), focusRing()] : []
    })
    cellLayer!.addChild(ds)
    renderedCellIds.add(c.id)
  }
}

watch(() => canvasStore.loaded, v => { if (v) render() })
// Watch the array references (the store replaces them immutably), not `.length`:
// same-length replacements must reconcile too.
watch(() => canvasStore.cells, () => render())
watch(() => canvasStore.tunnels, () => render())

watch(() => canvasStore.focusedId, id => {
  if (!cellLayer) return
  for (const c of cellLayer.children) {
    const s = c as any
    if (s._kind !== 'cell') continue
    s.filters = s._cellId === id ? [focusFilter(), focusRing()] : []
  }
})
</script>

<template><div ref="root" class="canvas-root" /></template>
<style scoped>.canvas-root { position:absolute; inset:0; }</style>
