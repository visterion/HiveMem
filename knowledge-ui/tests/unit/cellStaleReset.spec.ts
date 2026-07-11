import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { router } from '../../src/router'
import { resetApi } from '../../src/api/useApi'
import { MockApiClient } from '../../src/api/mockClient'
import { useCellStore } from '../../src/stores/cell'
import { useCanvasStore } from '../../src/stores/canvas'
import { i18n } from '../../src/i18n'
import KnowledgeReader from '../../src/components/knowledge/KnowledgeReader.vue'
import type { Cell } from '../../src/api/types'

const SOME_ID = 'stale-1'

function fullCell(id: string): Cell {
  return {
    id, realm: 'work', signal: 'facts', topic: 'topic',
    title: '', content: `content of ${id}`, summary: null,
    key_points: [], insight: null, tags: [], importance: 1,
    status: 'committed', created_by: 'x',
    created_at: '2026-01-01T00:00:00Z', valid_from: '2026-01-01T00:00:00Z', valid_until: null,
  }
}

function stubApi() {
  vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string, args?: Record<string, unknown>) => {
    if (tool === 'get_cell') return fullCell(args?.cell_id as string)
    if (tool === 'traverse') return { edges: [] }
    if (tool === 'entity_overview') return { cells: [], facts: [], tunnels: [] }
    if (tool === 'wake_up') return { role: 'admin', identity: 'me' }
    return {}
  })
}

describe('router afterEach — stale selection reset', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
    stubApi()
    await router.push({ name: 'search' })
    await router.isReady()
  })
  afterEach(() => vi.restoreAllMocks())

  it('route NAME change clears the selected cell and canvas focus', async () => {
    const cell = useCellStore()
    const canvas = useCanvasStore()
    await cell.load(SOME_ID)
    canvas.setFocus(SOME_ID)
    expect(cell.currentId).toBe(SOME_ID)

    await router.push({ name: 'graph' })

    expect(cell.currentId).toBeNull()
    expect(canvas.focusedId).toBeNull()
  })

  it('query-only change (facets/realm drilldown) keeps the selection', async () => {
    const cell = useCellStore()
    await cell.load(SOME_ID)
    expect(cell.currentId).toBe(SOME_ID)

    await router.push({ name: 'search', query: { realm: 'engineering' } })

    expect(cell.currentId).toBe(SOME_ID)
  })

  it('"Im Graph zeigen" (preserveOnce) keeps the selection across the search->graph navigation', async () => {
    const cell = useCellStore()
    await cell.load(SOME_ID)
    cell.preserveOnce = true

    await router.push({ name: 'graph' })

    expect(cell.currentId).toBe(SOME_ID)
    // The flag is consumed exactly once — a subsequent route-name change clears normally.
    await router.push({ name: 'search' })
    expect(cell.currentId).toBeNull()
  })

  it('navigating to /?cell=<id> from another route restores the cell (Task 8 interplay)', async () => {
    const cell = useCellStore()
    await router.push({ name: 'graph' }) // land somewhere else first

    await router.push({ name: 'search', query: { cell: SOME_ID } })
    const w = mount(KnowledgeReader, { global: { plugins: [router, i18n] } })
    await new Promise(r => setTimeout(r, 0))
    await new Promise(r => setTimeout(r, 0))

    expect(cell.currentId).toBe(SOME_ID)
    w.unmount()
  })
})
