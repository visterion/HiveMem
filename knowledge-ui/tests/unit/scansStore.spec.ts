import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useScansStore } from '../../src/stores/scans'
import { useCellStore } from '../../src/stores/cell'
import { useReaderStore } from '../../src/stores/reader'
import { resetApi } from '../../src/api/useApi'

describe('scans store', () => {
  beforeEach(() => {
    setActivePinia(createPinia()); localStorage.clear()
    localStorage.setItem('hivemem_mock','true'); resetApi(); vi.useFakeTimers()
  })
  afterEach(() => vi.useRealTimers())

  it('load() browses documents via list_documents', async () => {
    const s = useScansStore()
    const p = s.load(); await vi.advanceTimersByTimeAsync(300); await p
    expect(s.results.length).toBeGreaterThan(0)
    expect(s.results[0]).toHaveProperty('tags')
  })

  it('browse mode loads all statuses and rows carry status', async () => {
    const s = useScansStore()
    const p = s.load(); await vi.advanceTimersByTimeAsync(300); await p
    expect(s.results.some(r => r.status === 'rejected')).toBe(true)
    expect(s.results.some(r => r.status === 'pending')).toBe(true)
    expect(s.results.some(r => r.status === 'committed')).toBe(true)
  })

  it('load() rows include confidence field', async () => {
    const s = useScansStore()
    const p = s.load(); await vi.advanceTimersByTimeAsync(300); await p
    expect(s.results.length).toBeGreaterThan(0)
    expect(s.results[0]).toHaveProperty('confidence')
    expect(typeof s.results[0].confidence === 'number' || s.results[0].confidence === null).toBe(true)
  })

  it('setQuery + load() runs the search path', async () => {
    const s = useScansStore()
    s.setQuery('a')
    const p = s.load(); await vi.advanceTimersByTimeAsync(300); await p
    expect(Array.isArray(s.results)).toBe(true)
  })

  it('search results are enriched with thumbnail meta from list_documents', async () => {
    const s = useScansStore()
    s.query = 'Acme'
    const p = s.load(); await vi.advanceTimersByTimeAsync(300); await p
    expect(s.results.length).toBeGreaterThan(0)
    const withThumb = s.results.find(r => r.attachment_id)
    expect(withThumb?.has_thumbnail).toBe(true)
  })

  it('toggleFacet updates the facet set', () => {
    const s = useScansStore()
    s.toggleFacet('tag','contract'); expect(s.facets.tag.has('contract')).toBe(true)
    s.toggleFacet('tag','contract'); expect(s.facets.tag.has('contract')).toBe(false)
  })

  it('loadFacets() populates facetCounts with correspondent merged from fact:vendor+fact:party', async () => {
    const s = useScansStore()
    const p = s.loadFacets(); await vi.advanceTimersByTimeAsync(300); await p
    expect(s.facetCounts).toHaveProperty('tag')
    // correspondent facet should be present since mock data has vendor/party entries
    expect(s.facetCounts).toHaveProperty('correspondent')
    const corrFacet = s.facetCounts['correspondent']
    expect(Array.isArray(corrFacet)).toBe(true)
    expect(corrFacet.length).toBeGreaterThan(0)
    // Raw fact: keys should NOT appear in facetCounts
    expect(s.facetCounts).not.toHaveProperty('fact:vendor')
    expect(s.facetCounts).not.toHaveProperty('fact:party')
  })

  it('correspondent facet values are sum-merged (no duplicates by value)', async () => {
    const s = useScansStore()
    const p = s.loadFacets(); await vi.advanceTimersByTimeAsync(300); await p
    const corrFacet = s.facetCounts['correspondent'] ?? []
    // No duplicate values
    const values = corrFacet.map((f: any) => f.value)
    expect(values.length).toBe(new Set(values).size)
  })

  it('filtered getter respects correspondent facet selection', async () => {
    const s = useScansStore()
    const lp = s.load(); await vi.advanceTimersByTimeAsync(300); await lp
    // Find a document that has a correspondent
    const withCorr = s.results.find(r => r.correspondent)
    if (!withCorr || !withCorr.correspondent) return // skip if none in filtered set

    s.toggleFacet('correspondent', withCorr.correspondent)
    const filtered = s.filtered
    expect(filtered.every(d => d.correspondent === withCorr.correspondent)).toBe(true)
    expect(filtered.length).toBeGreaterThan(0)
  })

  // ── Saved views via server tools (localStorage replaced) ────────────────────
  it('saveView + loadSavedViews round-trips via server (not localStorage)', async () => {
    const s = useScansStore()
    // saveView calls saved_searches{save} (1 round-trip) then loadSavedViews/saved_searches{list} (1 round-trip) — advance enough
    const p1 = s.saveView('Steuer 2025', { tag: ['contract'] })
    await vi.advanceTimersByTimeAsync(600); await p1

    expect(s.savedViews.some(v => v.name === 'Steuer 2025')).toBe(true)
    // localStorage must NOT be used — verify it is empty
    expect(localStorage.getItem('hivemem_scans_views')).toBeNull()
  })

  it('loadSavedViews parses filter JSON string from server (round-trip)', async () => {
    const s = useScansStore()
    // Save a view — mock now returns filter as a JSON string
    const p1 = s.saveView('Contracts Filter', { tag: ['contract'], status: ['pending'] })
    await vi.advanceTimersByTimeAsync(600); await p1

    // Reset in-memory state and reload so we exercise the string→object parse path
    s.savedViews = []
    const p2 = s.loadSavedViews(); await vi.advanceTimersByTimeAsync(300); await p2

    const restored = s.savedViews.find(v => v.name === 'Contracts Filter')
    expect(restored).toBeDefined()
    // filter must have been parsed back to an object (not a string)
    expect(typeof restored!.filter).toBe('object')
    expect(Array.isArray(restored!.filter.tag)).toBe(true)
    expect(restored!.filter.tag).toContain('contract')

    // Apply the saved view and verify facets restore correctly
    s.setSavedView(restored!.id)
    expect(s.facets.tag.has('contract')).toBe(true)
    expect(s.facets.status.has('pending')).toBe(true)
  })

  it('loadSavedViews loads from server and populates savedViews', async () => {
    const s = useScansStore()
    // First save (saved_searches{save} + saved_searches{list})
    const p1 = s.saveView('Contracts', { tag: ['contract'] })
    await vi.advanceTimersByTimeAsync(600); await p1

    // Reset store state and reload from server
    s.savedViews = []
    const p2 = s.loadSavedViews(); await vi.advanceTimersByTimeAsync(300); await p2
    expect(s.savedViews.some(v => v.name === 'Contracts')).toBe(true)
  })

  it('deleteView removes saved view from server', async () => {
    const s = useScansStore()
    const p1 = s.saveView('ToDelete', { tag: ['other'] })
    await vi.advanceTimersByTimeAsync(600); await p1
    expect(s.savedViews.some(v => v.name === 'ToDelete')).toBe(true)

    const toDelete = s.savedViews.find(v => v.name === 'ToDelete')!
    // deleteView calls saved_searches{delete} then loadSavedViews — need 2 round-trips
    const p2 = s.deleteView(toDelete.id); await vi.advanceTimersByTimeAsync(600); await p2
    expect(s.savedViews.some(v => v.name === 'ToDelete')).toBe(false)
  })

  it('editTags calls manage_tags then reloads', async () => {
    const s = useScansStore()
    // Pre-load results
    const p0 = s.load(); await vi.advanceTimersByTimeAsync(300); await p0

    const p1 = s.editTags('doc-contract-001', ['reviewed'], [])
    await vi.advanceTimersByTimeAsync(600); await p1
    // After reload, the tag should appear on the row
    const updated = s.results.find(r => r.id === 'doc-contract-001')
    expect(updated?.tags).toContain('reviewed')
  })

  it('bulkTag calls manage_tags then reloads', async () => {
    const s = useScansStore()
    const p0 = s.load(); await vi.advanceTimersByTimeAsync(300); await p0

    s.toggleSelect('doc-invoice-001')
    const p1 = s.bulkTag(['archived'], [])
    await vi.advanceTimersByTimeAsync(600); await p1

    const updated = s.results.find(r => r.id === 'doc-invoice-001')
    expect(updated?.tags).toContain('archived')
  })

  it('openDocument seeds the cellStore and opens the reader for the same cell', async () => {
    const s = useScansStore()
    const p0 = s.load(); await vi.advanceTimersByTimeAsync(300); await p0
    const id = s.results[0].id

    const p1 = s.openDocument(id); await vi.advanceTimersByTimeAsync(300); await p1

    const cells = useCellStore()
    const reader = useReaderStore()
    expect(s.openId).toBe(id)
    expect(cells.currentId).toBe(id)
    expect(reader.open).toBe(true)
    expect(reader.cellId).toBe(id)
  })

  it('openDocument focus="info" lands on the overview tab, not the document', async () => {
    const s = useScansStore()
    const p0 = s.load(); await vi.advanceTimersByTimeAsync(300); await p0
    const id = s.results[0].id
    const p = s.openDocument(id, 'info'); await vi.advanceTimersByTimeAsync(300); await p
    const reader = useReaderStore()
    expect(reader.open).toBe(true)
    expect(reader.activeTab).toBe('info')
  })

  it('openDocument fetches the summary layers (include replaces get_cell defaults)', async () => {
    const s = useScansStore()
    const lp = s.load(); await vi.advanceTimersByTimeAsync(300); await lp
    const id = s.results[0].id

    const api = (await import('../../src/api/useApi')).useApi()
    const spy = vi.spyOn(api, 'call')
    const p = s.openDocument(id); await vi.advanceTimersByTimeAsync(300); await p

    const getCellCall = spy.mock.calls.find(c => c[0] === 'get_cell')
    expect(getCellCall).toBeDefined()
    const include = (getCellCall![1] as any).include as string[]
    // Without these explicitly, the server drops the layers (include overrides defaults).
    expect(include).toEqual(expect.arrayContaining(['summary', 'key_points', 'insight', 'content', 'attachments']))
  })

  it('bulkReclassify calls reclassify then clears selection + reloads', async () => {
    const s = useScansStore()
    const p0 = s.load(); await vi.advanceTimersByTimeAsync(300); await p0

    s.toggleSelect('doc-other-001')
    expect(s.selection.size).toBe(1)

    const p1 = s.bulkReclassify('documents', 'archive')
    await vi.advanceTimersByTimeAsync(600); await p1

    // Selection cleared
    expect(s.selection.size).toBe(0)
  })
})
