import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRoot } from 'react-dom/client'
import type { GraphNode } from '../../src/graph/types'
import { ForceGraphRoot } from '../../src/components/graph/ForceGraphRoot'
import ForceGraphBridge from '../../src/components/graph/ForceGraphBridge.vue'
import ScanPanel from '../../src/components/ScanPanel.vue'
import { useKeybindings } from '../../src/composables/keybindings'
import { useCanvasStore } from '../../src/stores/canvas'
import { useCellStore } from '../../src/stores/cell'
import { useReaderStore } from '../../src/stores/reader'
import { useUiStore } from '../../src/stores/ui'
import { i18n } from '../../src/i18n'

const reactRoot = {
  render: vi.fn(),
  unmount: vi.fn()
}

const resizeObserverState = {
  callback: null as ResizeObserverCallback | null,
  disconnect: vi.fn(),
  observe: vi.fn((target: Element) => {
    resizeObserverState.callback?.([
      {
        target,
        contentRect: {
          width: 640,
          height: 480
        }
      } as ResizeObserverEntry
    ], {} as ResizeObserver)
  })
}

vi.mock('react-dom/client', () => ({
  createRoot: vi.fn(() => reactRoot)
}))

class ResizeObserverMock {
  constructor(callback: ResizeObserverCallback) {
    resizeObserverState.callback = callback
  }

  observe = resizeObserverState.observe
  disconnect = resizeObserverState.disconnect
}

Object.defineProperty(globalThis, 'ResizeObserver', {
  value: ResizeObserverMock,
  configurable: true,
  writable: true
})

const TestKeybindingsHost = defineComponent({
  setup() {
    useKeybindings()
    return () => null
  }
})

function makeCell(id: string, title = 'Alpha', importance = 3) {
  return {
    id,
    title,
    realm: 'ops',
    signal: null,
    topic: null,
    importance
  }
}

function makeGraphNode(id: string, overrides: Partial<GraphNode> = {}): GraphNode {
  return {
    id,
    label: id,
    realm: 'ops',
    signal: null,
    topic: null,
    importance: 3,
    val: 3,
    color: '#123456',
    ...overrides
  }
}

async function flushPromises() {
  await Promise.resolve()
  await Promise.resolve()
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('graph interactions', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()

    const canvas = useCanvasStore()
    const cell = useCellStore()
    const reader = useReaderStore()
    const ui = useUiStore()

    canvas.cells = []
    canvas.tunnels = []
    canvas.setFocus(null)
    canvas.setHover(null)
    cell.cache.clear()
    cell.currentId = null
    cell.loading = false
    reader.$reset()
    ui.$reset()
  })

  it('translates ForceGraph2D hover and click events and preserves base node size', () => {
    const onNodeHover = vi.fn()
    const onNodeClick = vi.fn()
    const node = makeGraphNode('cell-1', { val: 5, x: 10, y: 20 })

    const hoveredGraphElement = ForceGraphRoot({
      nodes: [node],
      links: [],
      width: 640,
      height: 480,
      focusedId: null,
      hoveredId: 'cell-1',
      onNodeHover,
      onNodeClick
    })

    const forceGraphProps = hoveredGraphElement.props as any
    expect(forceGraphProps.graphData.nodes).toEqual([node])

    forceGraphProps.onNodeHover(node)
    forceGraphProps.onNodeHover(null)
    forceGraphProps.onNodeClick(node)
    forceGraphProps.onNodeClick({ id: 42 })

    expect(onNodeHover).toHaveBeenNthCalledWith(1, 'cell-1')
    expect(onNodeHover).toHaveBeenNthCalledWith(2, null)
    expect(onNodeClick).toHaveBeenCalledTimes(1)
    expect(onNodeClick).toHaveBeenCalledWith('cell-1')

    const hoveredCtx = {
      beginPath: vi.fn(),
      arc: vi.fn(),
      fill: vi.fn(),
      fillText: vi.fn(),
      measureText: vi.fn(() => ({ width: 10 })),
      save: vi.fn(),
      restore: vi.fn(),
      fillStyle: '',
      font: '',
      textAlign: '',
      textBaseline: '',
      globalAlpha: 1,
      shadowColor: '',
      shadowBlur: 0
    }

    forceGraphProps.nodeCanvasObject(node, hoveredCtx, 1)
    expect(hoveredCtx.arc).toHaveBeenCalledWith(10, 20, 7, 0, 2 * Math.PI)
    expect(hoveredCtx.fillText).toHaveBeenCalled()

    const focusedGraphElement = ForceGraphRoot({
      nodes: [node],
      links: [],
      width: 640,
      height: 480,
      focusedId: 'cell-1',
      hoveredId: null,
      onNodeHover,
      onNodeClick
    })

    const focusedProps = focusedGraphElement.props as any
    const focusedCtx = {
      beginPath: vi.fn(),
      arc: vi.fn(),
      fill: vi.fn(),
      fillText: vi.fn(),
      measureText: vi.fn(() => ({ width: 10 })),
      save: vi.fn(),
      restore: vi.fn(),
      fillStyle: '',
      font: '',
      textAlign: '',
      textBaseline: '',
      globalAlpha: 1,
      shadowColor: '',
      shadowBlur: 0
    }

    focusedProps.nodeCanvasObject(node, focusedCtx, 1)
    expect(focusedCtx.arc).toHaveBeenCalledWith(10, 20, 9, 0, 2 * Math.PI)
  })

  it('wires bridge hover and click through shared interaction state', async () => {
    const canvas = useCanvasStore()
    const cell = useCellStore()
    cell.load = vi.fn().mockResolvedValue(undefined)

    canvas.cells = [makeCell('cell-1', 'Alpha', 5), makeCell('cell-2', 'Beta', 2)] as any
    canvas.tunnels = [
      {
        id: 'tunnel-1',
        from_cell: 'cell-1',
        to_cell: 'cell-2',
        relation: 'related_to'
      }
    ] as any

    const wrapper = mount(ForceGraphBridge)
    await nextTick()

    expect(createRoot).toHaveBeenCalledTimes(1)

    const initialReactElement = reactRoot.render.mock.calls.at(-1)?.[0]
    expect(initialReactElement?.props.focusedId).toBe(null)
    expect(initialReactElement?.props.hoveredId).toBe(null)

    initialReactElement.props.onNodeHover('cell-1')
    await nextTick()
    expect(canvas.hoveredId).toBe('cell-1')

    const hoveredReactElement = reactRoot.render.mock.calls.at(-1)?.[0]
    expect(hoveredReactElement.props.hoveredId).toBe('cell-1')

    hoveredReactElement.props.onNodeHover(null)
    await nextTick()
    expect(canvas.hoveredId).toBe(null)

    const clearedReactElement = reactRoot.render.mock.calls.at(-1)?.[0]
    expect(clearedReactElement.props.hoveredId).toBe(null)

    clearedReactElement.props.onNodeClick('cell-1')
    await flushPromises()
    await nextTick()

    expect(canvas.focusedId).toBe('cell-1')
    expect(cell.load).toHaveBeenCalledWith('cell-1')

    const focusedReactElement = reactRoot.render.mock.calls.at(-1)?.[0]
    expect(focusedReactElement.props.focusedId).toBe('cell-1')

    wrapper.unmount()
    expect(reactRoot.unmount).toHaveBeenCalledTimes(1)
    expect(resizeObserverState.disconnect).toHaveBeenCalledTimes(1)
  })

  it('keeps existing detail and focus in sync during slow bridge navigation until load resolves', async () => {
    const canvas = useCanvasStore()
    const cell = useCellStore()
    const pending = deferred<void>()
    cell.load = vi.fn().mockReturnValue(pending.promise)

    cell.cache.set('cell-1', {
      cell: makeCell('cell-1'),
      facts: [],
      tunnels: []
    } as any)
    cell.currentId = 'cell-1'
    canvas.setFocus('cell-1')
    canvas.setHover('cell-2')
    canvas.cells = [makeCell('cell-1', 'Alpha', 5), makeCell('cell-2', 'Beta', 2)] as any
    canvas.tunnels = [] as any

    const wrapper = mount(ForceGraphBridge)
    await nextTick()

    const reactElement = reactRoot.render.mock.calls.at(-1)?.[0]
    reactElement.props.onNodeClick('cell-2')
    await nextTick()

    expect(cell.load).toHaveBeenCalledWith('cell-2')
    expect(cell.currentId).toBe('cell-1')
    expect(canvas.focusedId).toBe('cell-1')
    expect(canvas.hoveredId).toBe(null)

    const pendingReactElement = reactRoot.render.mock.calls.at(-1)?.[0]
    expect(pendingReactElement.props.focusedId).toBe('cell-1')
    expect(pendingReactElement.props.hoveredId).toBe(null)

    cell.currentId = 'cell-2'
    pending.resolve(undefined)
    await flushPromises()
    await nextTick()

    expect(canvas.focusedId).toBe('cell-2')

    const resolvedReactElement = reactRoot.render.mock.calls.at(-1)?.[0]
    expect(resolvedReactElement.props.focusedId).toBe('cell-2')

    wrapper.unmount()
  })

  it('resets shared interaction state when bridge detail loading fails', async () => {
    const canvas = useCanvasStore()
    const cell = useCellStore()
    cell.load = vi.fn().mockRejectedValue(new Error('load failed'))

    cell.cache.set('cell-0', {
      cell: makeCell('cell-0', 'Current'),
      facts: [],
      tunnels: []
    } as any)
    cell.currentId = 'cell-0'
    canvas.setFocus('cell-0')
    canvas.cells = [makeCell('cell-1', 'Alpha', 5)] as any
    canvas.tunnels = [] as any

    const wrapper = mount(ForceGraphBridge)
    await nextTick()

    const reactElement = reactRoot.render.mock.calls.at(-1)?.[0]
    reactElement.props.onNodeHover('cell-1')
    await nextTick()
    reactElement.props.onNodeClick('cell-1')
    await flushPromises()
    await nextTick()

    expect(cell.load).toHaveBeenCalledWith('cell-1')
    expect(canvas.focusedId).toBe('cell-0')
    expect(canvas.hoveredId).toBe(null)

    const rerenderedReactElement = reactRoot.render.mock.calls.at(-1)?.[0]
    expect(rerenderedReactElement.props.focusedId).toBe('cell-0')
    expect(rerenderedReactElement.props.hoveredId).toBe(null)

    wrapper.unmount()
  })

  it('scan panel close clears cell detail and shared hover/focus state', async () => {
    const canvas = useCanvasStore()
    const cell = useCellStore()

    cell.cache.set('cell-1', {
      cell: makeCell('cell-1'),
      facts: [],
      tunnels: []
    } as any)
    cell.currentId = 'cell-1'
    canvas.setFocus('cell-1')
    canvas.setHover('cell-2')

    const wrapper = mount(ScanPanel, {
      global: {
        plugins: [i18n],
        stubs: {
          'v-btn': defineComponent({
            emits: ['click'],
            template: '<button v-bind="$attrs" @click="$emit(\'click\')" />'
          }),
          'v-chip': true
        }
      }
    })
    await nextTick()

    expect(wrapper.find('aside.scan').exists()).toBe(true)

    await wrapper.get('[data-testid="scan-panel-close"]').trigger('click')

    expect(cell.currentId).toBe(null)
    expect(canvas.focusedId).toBe(null)
    expect(canvas.hoveredId).toBe(null)
  })

  it('scan panel tunnel navigation clears stale hover while moving focus', async () => {
    const canvas = useCanvasStore()
    const cell = useCellStore()
    cell.load = vi.fn().mockResolvedValue(undefined)

    cell.cache.set('cell-1', {
      cell: makeCell('cell-1'),
      facts: [],
      tunnels: [
        {
          id: 'tunnel-1',
          from_cell: 'cell-1',
          to_cell: 'cell-2',
          relation: 'related_to',
          note: null
        }
      ]
    } as any)
    cell.currentId = 'cell-1'
    canvas.setFocus('cell-1')
    canvas.setHover('cell-1')

    const wrapper = mount(ScanPanel, {
      global: {
        plugins: [i18n],
        stubs: {
          'v-btn': defineComponent({
            emits: ['click'],
            template: '<button v-bind="$attrs" @click="$emit(\'click\')" />'
          }),
          'v-chip': true
        }
      }
    })
    await nextTick()

    await wrapper.get('.tunnel').trigger('click')
    await flushPromises()
    await nextTick()

    expect(cell.load).toHaveBeenCalledWith('cell-2')
    expect(canvas.focusedId).toBe('cell-2')
    expect(canvas.hoveredId).toBe(null)
  })

  it('keeps existing detail and focus in sync during slow scan navigation until load resolves', async () => {
    const canvas = useCanvasStore()
    const cell = useCellStore()
    const pending = deferred<void>()
    cell.load = vi.fn().mockReturnValue(pending.promise)

    cell.cache.set('cell-1', {
      cell: makeCell('cell-1'),
      facts: [],
      tunnels: [
        {
          id: 'tunnel-1',
          from_cell: 'cell-1',
          to_cell: 'cell-2',
          relation: 'related_to',
          note: null
        }
      ]
    } as any)
    cell.currentId = 'cell-1'
    canvas.setFocus('cell-1')
    canvas.setHover('cell-1')

    const wrapper = mount(ScanPanel, {
      global: {
        plugins: [i18n],
        stubs: {
          'v-btn': defineComponent({
            emits: ['click'],
            template: '<button v-bind="$attrs" @click="$emit(\'click\')" />'
          }),
          'v-chip': true
        }
      }
    })
    await nextTick()

    await wrapper.get('.tunnel').trigger('click')
    await nextTick()

    expect(cell.load).toHaveBeenCalledWith('cell-2')
    expect(cell.currentId).toBe('cell-1')
    expect(canvas.focusedId).toBe('cell-1')
    expect(canvas.hoveredId).toBe(null)

    cell.currentId = 'cell-2'
    pending.resolve(undefined)
    await flushPromises()
    await nextTick()

    expect(canvas.focusedId).toBe('cell-2')
  })

  it('resets shared interaction state when scan panel navigation loading fails', async () => {
    const canvas = useCanvasStore()
    const cell = useCellStore()
    cell.load = vi.fn().mockRejectedValue(new Error('load failed'))

    cell.cache.set('cell-1', {
      cell: makeCell('cell-1'),
      facts: [],
      tunnels: [
        {
          id: 'tunnel-1',
          from_cell: 'cell-1',
          to_cell: 'cell-2',
          relation: 'related_to',
          note: null
        }
      ]
    } as any)
    cell.currentId = 'cell-1'
    canvas.setFocus('cell-1')
    canvas.setHover('cell-1')

    const wrapper = mount(ScanPanel, {
      global: {
        plugins: [i18n],
        stubs: {
          'v-btn': defineComponent({
            emits: ['click'],
            template: '<button v-bind="$attrs" @click="$emit(\'click\')" />'
          }),
          'v-chip': true
        }
      }
    })
    await nextTick()

    await wrapper.get('.tunnel').trigger('click')
    await flushPromises()
    await nextTick()

    expect(cell.load).toHaveBeenCalledWith('cell-2')
    expect(canvas.focusedId).toBe('cell-1')
    expect(canvas.hoveredId).toBe(null)
  })

  it('Escape clears cell detail and shared graph interaction state', async () => {
    const canvas = useCanvasStore()
    const cell = useCellStore()

    cell.cache.set('cell-1', {
      cell: makeCell('cell-1'),
      facts: [],
      tunnels: []
    } as any)
    cell.currentId = 'cell-1'
    canvas.setFocus('cell-1')
    canvas.setHover('cell-2')

    const wrapper = mount(TestKeybindingsHost)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await nextTick()

    expect(cell.currentId).toBe(null)
    expect(canvas.focusedId).toBe(null)
    expect(canvas.hoveredId).toBe(null)

    wrapper.unmount()
  })
})
