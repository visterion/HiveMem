import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useCanvasStore } from '../../src/stores/canvas'
import type { Cell, Realm } from '../../src/api/types'

// SphereCanvas draws through pixi.js (WebGL), which doesn't run in happy-dom.
// Mock it with lightweight fakes that keep the same shape (children arrays,
// addChild/removeChild) so render()'s reconciliation logic runs for real, while
// registries let the test reach inside the otherwise-unexposed <script setup>
// closures (world/realmLayer/... are plain local variables, not defineExpose'd).
const { allApplications, allContainers } = vi.hoisted(() => ({
  allApplications: [] as any[],
  allContainers: [] as any[],
}))

vi.mock('pixi.js', () => {
  class FakeContainer {
    children: any[] = []
    addChild(c: any) { this.children.push(c); return c }
    addChildAt(c: any) { this.children.push(c); return c }
    removeChild(c: any) { this.children = this.children.filter(x => x !== c); return c }
    constructor() { allContainers.push(this) }
  }
  class FakeApplication {
    screen = { width: 800, height: 600 }
    stage = new FakeContainer()
    // A real <canvas> element: SphereCanvas does `root.value.appendChild(app.canvas)`,
    // which needs an actual DOM node under happy-dom.
    canvas: HTMLCanvasElement = document.createElement('canvas')
    ticker = { add: () => {} }
    constructor() { allApplications.push(this) }
    async init() { /* no-op: screen dims are set directly by the test */ }
    destroy() { /* no-op */ }
  }
  class FakeSprite {
    anchor = { set: () => {} }
    scale = { x: 1, y: 1, set(v: number) { this.x = v; this.y = v } }
    width = 0; height = 0; x = 0; y = 0; alpha = 1; tint = 0xffffff
    eventMode = ''; cursor = ''; filters: any[] = []
    _handlers: Record<string, () => void> = {}
    constructor(public texture: unknown) {}
    on(evt: string, fn: () => void) { this._handlers[evt] = fn }
  }
  class FakeText extends FakeSprite {
    text: string
    constructor(opts: { text: string; style: unknown }) { super(opts.style); this.text = opts.text }
  }
  class FakeGraphics {
    rect() { return this }
    fill() { return this }
    clear() { return this }
    moveTo() { return this }
    lineTo() { return this }
    stroke() { return this }
  }
  class FakeTextStyle { constructor(_opts: unknown) {} }
  return {
    Application: FakeApplication,
    Container: FakeContainer,
    Sprite: FakeSprite,
    Graphics: FakeGraphics,
    Text: FakeText,
    TextStyle: FakeTextStyle,
  }
})

vi.mock('../../src/components/canvas/textures', () => ({
  realmTexture: () => ({}),
  cellTexture: () => ({}),
  colorForRealm: () => '#ffffff',
  parseHsl: () => 0xffffff,
}))
vi.mock('../../src/components/canvas/filters', () => ({
  focusFilter: () => ({}),
  hoverFilter: () => ({}),
  focusRing: () => ({}),
  godrays: () => ({}),
}))
vi.mock('../../src/components/canvas/particles', () => ({
  spawnDust: () => () => {},
}))

// Imported after the mocks above so it picks them up.
const { default: SphereCanvas } = await import('../../src/components/canvas/SphereCanvas.vue')

function makeCell(id: string, realm: string): Cell {
  return {
    id, realm, signal: 'facts', topic: null, title: id, content: '', summary: null,
    key_points: [], insight: null, tags: [], importance: 2, status: 'committed',
    created_by: 'me', created_at: '2026-01-01T00:00:00Z', valid_from: '2026-01-01T00:00:00Z',
    valid_until: null,
  }
}
function makeRealm(name: string, count: number): Realm {
  return { name, cell_count: count, signals: [{ name: 'facts', cell_count: count }] }
}

function allSprites(kind: string) {
  return allContainers.flatMap(c => c.children).filter((s: any) => s._kind === kind)
}

describe('SphereCanvas — reposition existing sprites on layout change (E5)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    allApplications.length = 0
    allContainers.length = 0
  })

  it('moves already-created realm/signal/cell sprites to the new layout instead of leaving them at creation-time coordinates', async () => {
    const canvas = useCanvasStore()
    canvas.realms = [makeRealm('engineering', 3), makeRealm('ops', 3)]
    canvas.cells = [makeCell('c1', 'engineering'), makeCell('c2', 'engineering'), makeCell('c3', 'ops')]
    canvas.tunnels = []

    const w = mount(SphereCanvas)
    await flushPromises()
    canvas.loaded = true // triggers the initial render()
    await flushPromises()

    const realmSprite = allSprites('realm').find((s: any) => s._name === 'engineering')
    const cellSprite = allSprites('cell').find((s: any) => s._cellId === 'c1')
    expect(realmSprite).toBeTruthy()
    expect(cellSprite).toBeTruthy()
    const initialRealmX = realmSprite.x, initialRealmY = realmSprite.y
    const initialCellX = cellSprite.x, initialCellY = cellSprite.y

    // Simulate a resize: the viewport changes, invalidating the cached realm
    // layout, so computeWingPositions() (deterministic given viewport size)
    // produces different coordinates.
    const app = allApplications[0]
    app.screen = { width: 1600, height: 1200 }
    // Any cells/tunnels array replacement re-triggers render() (watched by
    // reference, not `.length`).
    canvas.cells = [...canvas.cells]
    await flushPromises()

    const realmSpriteAfter = allSprites('realm').find((s: any) => s._name === 'engineering')
    const cellSpriteAfter = allSprites('cell').find((s: any) => s._cellId === 'c1')
    // Same sprite instances (reconciled, not recreated) …
    expect(realmSpriteAfter).toBe(realmSprite)
    expect(cellSpriteAfter).toBe(cellSprite)
    // … but repositioned to match the new layout.
    expect(realmSpriteAfter.x !== initialRealmX || realmSpriteAfter.y !== initialRealmY).toBe(true)
    expect(cellSpriteAfter.x !== initialCellX || cellSpriteAfter.y !== initialCellY).toBe(true)

    w.unmount()
  })
})
