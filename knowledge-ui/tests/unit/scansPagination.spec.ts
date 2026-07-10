import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useScansStore } from '../../src/stores/scans'
import { useReaderStore } from '../../src/stores/reader'
import { useUiStore } from '../../src/stores/ui'
import { resetApi } from '../../src/api/useApi'
import { MockApiClient } from '../../src/api/mockClient'
import type { DocumentRow } from '../../src/api/types'

function docRow(id: string): DocumentRow {
  return {
    id, realm: 'documents', signal: 'facts', topic: null, summary: id,
    tags: [], importance: 1, status: 'committed', created_at: '2026-01-01T00:00:00Z',
  }
}

describe('scans store — pagination, stale responses, status facet', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
  })
  afterEach(() => vi.restoreAllMocks())

  it('loadMore() advances the offset and appends the next page (M54)', async () => {
    const calls: Array<Record<string, unknown>> = []
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string, args?: Record<string, unknown>) => {
      if (tool !== 'list_documents') return {}
      calls.push(args ?? {})
      const offset = (args?.offset as number) ?? 0
      if (offset === 0) return Array.from({ length: 100 }, (_, i) => docRow(`d${i}`))
      return Array.from({ length: 40 }, (_, i) => docRow(`d${offset + i}`))
    })
    const s = useScansStore()
    await s.load()
    expect(s.results.length).toBe(100)
    expect(s.hasMore).toBe(true)

    await s.loadMore()
    expect(calls[1].offset).toBe(100)
    expect(s.results.length).toBe(140)
    expect(s.offset).toBe(140)
    expect(s.hasMore).toBe(false)

    await s.loadMore() // exhausted → no further request
    expect(calls.length).toBe(2)
  })

  it('a stale slower response never overwrites a newer one (M53)', async () => {
    let resolveFirst!: (rows: DocumentRow[]) => void
    let call = 0
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation((tool: string) => {
      if (tool !== 'list_documents') return Promise.resolve({})
      call++
      if (call === 1) return new Promise(res => { resolveFirst = res })
      return Promise.resolve([docRow('new')])
    })
    const s = useScansStore()
    const p1 = s.load()
    const p2 = s.load()
    await p2
    expect(s.results.map(d => d.id)).toEqual(['new'])
    resolveFirst([docRow('old')])
    await p1
    expect(s.results.map(d => d.id)).toEqual(['new'])
    expect(s.loading).toBe(false)
  })

  it('status facet is single-select, other facets stay multi-select (L-F8)', () => {
    const s = useScansStore()
    s.toggleFacet('status', 'pending')
    s.toggleFacet('status', 'committed')
    expect([...s.facets.status]).toEqual(['committed'])
    s.toggleFacet('status', 'committed') // toggle off
    expect(s.facets.status.size).toBe(0)

    s.toggleFacet('tag', 'a')
    s.toggleFacet('tag', 'b')
    expect(s.facets.tag.size).toBe(2)
  })

  it('search rows are not nuked by a stale correspondent facet selection (M17)', async () => {
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string, args?: Record<string, unknown>) => {
      if (tool === 'search') {
        expect((args?.include as string[])).toContain('summary')
        return Array.from({ length: 5 }, (_, i) => docRow(`s${i}`))
      }
      return {}
    })
    const s = useScansStore()
    s.facets.correspondent.add('Finanzamt') // selected while browsing; still active when a query starts
    s.setQuery('rent')
    await s.load()
    expect(s.results.length).toBe(5)
    expect(s.filtered.length).toBe(5) // must NOT be filtered to 0 (search rows have no `correspondent`)
    expect(s.results.every(r => (r as any).isSearchRow)).toBe(true)
  })

  it('search mode surfaces truncation instead of paginating (M54)', async () => {
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string) => {
      if (tool === 'search') return Array.from({ length: 100 }, (_, i) => docRow(`s${i}`))
      return {}
    })
    const s = useScansStore()
    s.setQuery('x')
    await s.load()
    expect(s.hasMore).toBe(false)
    expect(s.searchTruncated).toBe(true)
    await s.loadMore() // must be a no-op in search mode
    expect(s.results.length).toBe(100)
  })

  it('openDocument does not open the reader when the cell cannot be loaded (M55)', async () => {
    vi.spyOn(MockApiClient.prototype, 'call').mockRejectedValue(new Error('boom'))
    const s = useScansStore()
    const reader = useReaderStore()
    const ui = useUiStore()
    await s.openDocument('missing-id')
    expect(reader.open).toBe(false)
    expect(s.openId).toBeNull()
    expect(ui.toast?.kind).toBe('error')
  })

  it('openDocument falls back to a plain load when only the rich fetch fails (M55)', async () => {
    const orig = MockApiClient.prototype.call
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(function (
      this: MockApiClient, tool: string, args?: Record<string, unknown>,
    ) {
      // Only the rich, include-based get_cell fails; the plain fallback works.
      if (tool === 'get_cell' && args?.include) return Promise.reject(new Error('boom'))
      return orig.call(this, tool, args)
    })
    const s = useScansStore()
    await s.load()
    const id = s.results[0].id
    await s.openDocument(id)
    const reader = useReaderStore()
    expect(reader.open).toBe(true)
    expect(reader.cellId).toBe(id)
  })
})
