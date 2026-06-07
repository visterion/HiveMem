import { beforeEach, describe, it, expect, vi } from 'vitest'
import { nextTick, reactive } from 'vue'
import { mount } from '@vue/test-utils'
import { createRoot } from 'react-dom/client'
import GraphRoute from '../../src/pages/GraphRoute.vue'
import { i18n } from '../../src/i18n'

const loadTopLevel = vi.fn()
const loadCell = vi.fn()
const setHover = vi.fn()
const setFocus = vi.fn()
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
const canvasState = reactive({
  cells: [],
  loaded: false,
  loadTopLevel,
  setFocus,
  setHover,
  tunnels: []
})

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

vi.mock('../../src/stores/ui', () => ({
  useUiStore: () => ({
    activePanel: 'search',
    sizeMetric: 'cell_count',
    theme: 'dark'
  })
}))

vi.mock('../../src/stores/canvas', () => ({
  useCanvasStore: () => canvasState
}))

vi.mock('../../src/stores/cell', () => ({
  useCellStore: () => ({
    load: loadCell
  })
}))

vi.mock('../../src/composables/keybindings', () => ({
  useKeybindings: vi.fn()
}))

describe('graph route', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'de'
    canvasState.loaded = false
    canvasState.cells = []
    canvasState.tunnels = []
    vi.clearAllMocks()
  })

  it('registers a /graph route', async () => {
    const { router } = await import('../../src/router')
    const match = router.resolve('/graph')
    expect(match.name).toBe('graph')
  })

  it('renders the graph skeleton and triggers the initial load', async () => {
    const wrapper = mount(GraphRoute, {
      global: {
        plugins: [i18n],
        stubs: {
          IconRail: true,
          SearchPanel: true,
          RealmsPanel: true,
          SettingsPanel: true,
          ScanPanel: true,
          Reader: true,
          'v-btn': true
        }
      }
    })
    await nextTick()

    // Rail/side-panels are owned by AppShell now (SP-A); this route is just the
    // graph stage. While the canvas store is not loaded it shows the loading splash.
    expect(wrapper.find('.graph-stage').exists()).toBe(true)
    expect(wrapper.find('.splash').text()).toBe('Lädt…')
    expect(createRoot).not.toHaveBeenCalled()
    expect(loadTopLevel).toHaveBeenCalledTimes(1)
  })

  it('keeps shared detail surfaces on the graph route', () => {
    canvasState.loaded = true
    const wrapper = mount(GraphRoute, {
      global: {
        plugins: [i18n],
        stubs: {
          IconRail: true,
          SearchPanel: true,
          RealmsPanel: true,
          SettingsPanel: true,
          ScanPanel: true,
          Reader: true,
          ForceGraphBridge: true,
          'v-btn': true
        }
      }
    })

    expect(wrapper.html()).toContain('force-graph-bridge')
  })

  it('mounts the real graph bridge and wires React root props', async () => {
    canvasState.loaded = true
    canvasState.cells = [
      {
        id: 'cell-1',
        title: 'Alpha',
        realm: 'ops',
        signal: null,
        topic: null,
        importance: 3
      },
      {
        id: 'cell-2',
        title: 'Beta',
        realm: 'ops',
        signal: null,
        topic: null,
        importance: 1
      }
    ] as any
    canvasState.tunnels = [
      {
        id: 'tunnel-1',
        from_cell: 'cell-1',
        to_cell: 'cell-2',
        relation: 'related_to'
      }
    ] as any

    const wrapper = mount(GraphRoute, {
      global: {
        plugins: [i18n],
        stubs: {
          IconRail: true,
          SearchPanel: true,
          RealmsPanel: true,
          SettingsPanel: true,
          ScanPanel: true,
          Reader: true,
          'v-btn': true
        }
      }
    })
    await nextTick()

    expect(wrapper.find('[data-testid="force-graph-bridge"]').exists()).toBe(true)
    expect(createRoot).toHaveBeenCalled()

    const reactElement = reactRoot.render.mock.calls.at(-1)?.[0]
    expect(reactElement?.props.nodes).toHaveLength(2)
    expect(reactElement?.props.links).toHaveLength(1)
    expect(reactElement?.props.width).toBe(640)
    expect(reactElement?.props.height).toBe(480)
    expect(reactElement?.props.nodes[0]?.color).toBeTruthy()

    const forceGraphElement = reactElement?.type(reactElement.props)
    expect(forceGraphElement?.props.nodeColor).toBe('color')
    expect(forceGraphElement?.props.nodeAutoColorBy).toBeUndefined()

    reactElement.props.onNodeHover('cell-1')
    reactElement.props.onNodeClick('cell-2')

    expect(setHover).toHaveBeenCalledWith('cell-1')
    expect(setFocus).toHaveBeenCalledWith('cell-2')
    expect(loadCell).toHaveBeenCalledWith('cell-2')

    reactRoot.render.mockClear()
    canvasState.cells = [
      ...canvasState.cells,
      {
        id: 'cell-3',
        title: 'Gamma',
        realm: 'research',
        signal: null,
        topic: null,
        importance: 2
      }
    ] as any
    await nextTick()

    const rerenderedElement = reactRoot.render.mock.calls.at(-1)?.[0]
    expect(rerenderedElement?.props.nodes).toHaveLength(3)

    wrapper.unmount()
    expect(reactRoot.unmount).toHaveBeenCalledTimes(1)
    expect(resizeObserverState.disconnect).toHaveBeenCalledTimes(1)
  })
})
