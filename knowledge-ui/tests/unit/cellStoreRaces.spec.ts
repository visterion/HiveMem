import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApi } from '../../src/api/useApi'
import { MockApiClient } from '../../src/api/mockClient'
import { useCellStore } from '../../src/stores/cell'
import type { Cell } from '../../src/api/types'

function fullCell(id: string): Cell {
  return {
    id, realm: 'work', signal: 'facts', topic: 'topic',
    title: '', content: `content of ${id}`, summary: null,
    key_points: [], insight: null, tags: [], importance: 1,
    status: 'committed', created_by: 'x',
    created_at: '2026-01-01T00:00:00Z', valid_from: '2026-01-01T00:00:00Z', valid_until: null,
  }
}

/** Spy that answers get_cell/traverse/entity_overview; get_cell for `slowId` stays pending until resolved. */
function stubApi(slowId?: string) {
  let resolveSlow!: (c: Cell) => void
  const slow = new Promise<Cell>(res => { resolveSlow = res })
  vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string, args?: Record<string, unknown>) => {
    if (tool === 'get_cell') {
      const id = args?.cell_id as string
      return (slowId && id === slowId) ? slow : fullCell(id)
    }
    if (tool === 'traverse') return { edges: [] }
    if (tool === 'entity_overview') return { cells: [], facts: [], tunnels: [] }
    return {}
  })
  return { resolveSlow }
}

describe('cell store — races, cache merge, LRU', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
  })
  afterEach(() => vi.restoreAllMocks())

  it('out-of-order load responses do not clobber the latest selection (M52)', async () => {
    const { resolveSlow } = stubApi('a')
    const store = useCellStore()
    const pa = store.load('a') // slow
    const pb = store.load('b') // fast
    await pb
    expect(store.currentId).toBe('b')
    expect(store.loading).toBe(false)
    resolveSlow(fullCell('a')) // stale response arrives late
    await pa
    expect(store.currentId).toBe('b')
    expect(store.loading).toBe(false)
    // the stale response is still cached for later use
    expect(store.cache.has('a')).toBe(true)
  })

  it('load() clears a stale search score breakdown (L-F9)', async () => {
    stubApi()
    const store = useCellStore()
    store.selectedScores = { ...fullCell('a'), score_total: 0.5 } as never
    await store.load('b')
    expect(store.selectedScores).toBeNull()
  })

  it('ensureAttachments merges the full get_cell row into a partial cached row (C3)', async () => {
    const store = useCellStore()
    // Partial search row: no content, no tags, no attachments (server `include`
    // replaced the default field set).
    const partial = {
      id: 'p1', realm: 'work', signal: 'facts', topic: null, summary: 'Partial row',
      status: 'committed', created_at: '2026-01-01T00:00:00Z',
      valid_from: '2026-01-01T00:00:00Z', valid_until: null,
    } as unknown as Cell
    const att = { id: 'a1', mime_type: 'application/pdf', original_filename: 'f.pdf', size_bytes: 3 }
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string) => {
      if (tool === 'get_cell') return { ...fullCell('p1'), content: 'real content', tags: ['t1'], attachments: [att] }
      if (tool === 'traverse') return { edges: [] }
      if (tool === 'entity_overview') return { cells: [], facts: [], tunnels: [] }
      return {}
    })
    await store.open(partial)
    expect((store.current?.cell as Partial<Cell>).content).toBeUndefined()

    await store.ensureAttachments('p1')
    expect(store.current?.cell.content).toBe('real content')
    expect(store.current?.cell.tags).toEqual(['t1'])
    expect(store.current?.cell.attachments).toEqual([att])
    // locally-known fields are never overwritten by the merge
    expect(store.current?.cell.summary).toBe('Partial row')
  })

  it('open() upgrades an already-cached partial row with richer fields (C3)', async () => {
    stubApi()
    const store = useCellStore()
    const partial = { id: 'p2', realm: 'work', summary: 'S' } as unknown as Cell
    await store.open(partial)
    const rich = { ...fullCell('p2'), summary: 'S' }
    await store.open(rich)
    expect(store.current?.cell.content).toBe('content of p2')
  })

  it('cache eviction never removes the currently-displayed cell (L-F10)', () => {
    const store = useCellStore()
    store.store('keep', { cell: fullCell('keep'), facts: [], tunnels: [] })
    store.currentId = 'keep'
    for (let i = 0; i < 60; i++) {
      store.store(`x${i}`, { cell: fullCell(`x${i}`), facts: [], tunnels: [] })
    }
    expect(store.cache.has('keep')).toBe(true)
    expect(store.cache.size).toBeLessThanOrEqual(51)
  })

  it('touch() moves a cache hit to the back so LRU eviction spares it', () => {
    const store = useCellStore()
    store.store('a', { cell: fullCell('a'), facts: [], tunnels: [] })
    store.store('b', { cell: fullCell('b'), facts: [], tunnels: [] })
    store.touch('a')
    const keys = [...store.cache.keys()]
    expect(keys[keys.length - 1]).toBe('a')
  })
})
