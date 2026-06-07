import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useScansStore } from '../../src/stores/scans'
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
  it('setQuery + load() runs the search path', async () => {
    const s = useScansStore()
    s.setQuery('a')
    const p = s.load(); await vi.advanceTimersByTimeAsync(300); await p
    expect(Array.isArray(s.results)).toBe(true)
  })
  it('toggleFacet updates the facet set', () => {
    const s = useScansStore()
    s.toggleFacet('tag','contract'); expect(s.facets.tag.has('contract')).toBe(true)
    s.toggleFacet('tag','contract'); expect(s.facets.tag.has('contract')).toBe(false)
  })
  it('loadFacets() populates facetCounts', async () => {
    const s = useScansStore()
    const p = s.loadFacets(); await vi.advanceTimersByTimeAsync(300); await p
    expect(s.facetCounts).toHaveProperty('tag')
  })
  it('saved views round-trip through localStorage', () => {
    const s = useScansStore()
    s.saveView('Steuer 2025', { tag: ['contract'] })
    expect(s.savedViews.some(v => v.name === 'Steuer 2025')).toBe(true)
    const s2 = useScansStore(); s2.loadSavedViews()
    expect(s2.savedViews.some(v => v.name === 'Steuer 2025')).toBe(true)
  })
})
