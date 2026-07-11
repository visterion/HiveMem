import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import ScansResults from '../../src/components/scans/ScansResults.vue'
import { i18n } from '../../src/i18n/index'
import { resetApi } from '../../src/api/useApi'
import { MockApiClient } from '../../src/api/mockClient'
import { useScansStore } from '../../src/stores/scans'

describe('ScansResults', () => {
  beforeEach(() => { setActivePinia(createPinia()); i18n.global.locale.value='de'
    localStorage.setItem('hivemem_mock','true'); resetApi(); vi.useFakeTimers() })
  afterEach(() => vi.useRealTimers())

  it('renders doc cards from the store and shows a bulk bar on selection', async () => {
    const vuetify = createVuetify({ components, directives })
    const w = mount(ScansResults, { global: { plugins: [i18n, vuetify] } })
    await vi.advanceTimersByTimeAsync(500); await flushPromises()  // initial reload (mock list_documents)
    const s = useScansStore()
    expect(s.results.length).toBeGreaterThan(0)
    expect(w.findAll('.doccard').length).toBeGreaterThan(0)
    // selecting shows the bulk bar
    s.toggleSelect(s.results[0].id)
    await flushPromises()
    expect(w.find('.bulkbar').exists()).toBe(true)
  })

  it('bulk Tag button calls store.bulkTag when prompted', async () => {
    const vuetify = createVuetify({ components, directives })
    const w = mount(ScansResults, { global: { plugins: [i18n, vuetify] } })
    await vi.advanceTimersByTimeAsync(500); await flushPromises()
    const s = useScansStore()
    s.toggleSelect(s.results[0].id)
    await flushPromises()

    const bulkTagSpy = vi.spyOn(s, 'bulkTag').mockResolvedValue()
    vi.stubGlobal('prompt', () => 'invoice,contract')
    await w.findAll('.bulk-btn')[0].trigger('click')
    await flushPromises()
    expect(bulkTagSpy).toHaveBeenCalledWith(['invoice', 'contract'])
    vi.unstubAllGlobals()
  })

  it('bulk Realm button calls store.bulkReclassify when prompted', async () => {
    const vuetify = createVuetify({ components, directives })
    const w = mount(ScansResults, { global: { plugins: [i18n, vuetify] } })
    await vi.advanceTimersByTimeAsync(500); await flushPromises()
    const s = useScansStore()
    s.toggleSelect(s.results[0].id)
    await flushPromises()

    const reclassSpy = vi.spyOn(s, 'bulkReclassify').mockResolvedValue()
    vi.stubGlobal('prompt', () => 'archive')
    await w.findAll('.bulk-btn')[1].trigger('click')
    await flushPromises()
    expect(reclassSpy).toHaveBeenCalledWith('archive')
    vi.unstubAllGlobals()
  })

  it('rejected doc card shows a status badge; committed does not (Task 6)', async () => {
    const vuetify = createVuetify({ components, directives })
    const w = mount(ScansResults, { global: { plugins: [i18n, vuetify] } })
    await vi.advanceTimersByTimeAsync(500); await flushPromises()
    const s = useScansStore()
    const rejectedRow = s.results.find(r => r.status === 'rejected')
    const committedRow = s.results.find(r => r.status === 'committed')
    expect(rejectedRow).toBeTruthy()
    expect(committedRow).toBeTruthy()

    const cards = w.findAll('.doccard')
    const rejectedIdx = s.results.findIndex(r => r.id === rejectedRow!.id)
    const committedIdx = s.results.findIndex(r => r.id === committedRow!.id)

    const rejectedBadge = cards[rejectedIdx].find('[data-test="dc-status"]')
    expect(rejectedBadge.exists()).toBe(true)
    expect(rejectedBadge.text()).toBe('abgelehnt')

    const committedBadge = cards[committedIdx].find('[data-test="dc-status"]')
    expect(committedBadge.exists()).toBe(false)
  })

  it('correspondent facet chips appear in filter chips when set', async () => {
    const vuetify = createVuetify({ components, directives })
    const w = mount(ScansResults, { global: { plugins: [i18n, vuetify] } })
    await vi.advanceTimersByTimeAsync(500); await flushPromises()
    const s = useScansStore()
    s.facets.correspondent.add('Finanzamt')
    await flushPromises()
    expect(w.text()).toContain('Finanzamt')
  })

  it('shows an error state with retry when reload fails, not a misleading "no results" (E5)', async () => {
    let calls = 0
    const spy = vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string) => {
      if (tool === 'list_documents') { calls++; if (calls === 1) throw new Error('boom'); return [] }
      return {}
    })
    const vuetify = createVuetify({ components, directives })
    const w = mount(ScansResults, { global: { plugins: [i18n, vuetify] } })
    await vi.advanceTimersByTimeAsync(500); await flushPromises()
    expect(w.find('.empty.error').exists()).toBe(true)
    expect(w.find('.retry-btn').exists()).toBe(true)
    // no "no results" text shown for the failed load
    expect(w.text()).not.toContain('Keine Dokumente gefunden')

    await w.find('.retry-btn').trigger('click')
    await vi.advanceTimersByTimeAsync(500); await flushPromises()
    expect(w.find('.empty.error').exists()).toBe(false)
    spy.mockRestore()
  })

  describe('infinite scroll (H8)', () => {
    let observeCb: ((entries: { isIntersecting: boolean }[]) => void) | null = null
    let originalIO: any

    beforeEach(() => {
      originalIO = (globalThis as any).IntersectionObserver
      ;(globalThis as any).IntersectionObserver = class {
        constructor(cb: (entries: { isIntersecting: boolean }[]) => void) { observeCb = cb }
        observe() {}
        unobserve() {}
        disconnect() {}
      }
    })
    afterEach(() => { (globalThis as any).IntersectionObserver = originalIO; observeCb = null })

    it('calls store.loadMore() when the sentinel intersects and hasMore is true', async () => {
      const vuetify = createVuetify({ components, directives })
      const w = mount(ScansResults, { global: { plugins: [i18n, vuetify] } })
      await vi.advanceTimersByTimeAsync(500); await flushPromises()
      const s = useScansStore()
      s.hasMore = true
      const spy = vi.spyOn(s, 'loadMore').mockResolvedValue()
      observeCb!([{ isIntersecting: true }])
      expect(spy).toHaveBeenCalled()
      w.unmount()
    })

    it('does not call store.loadMore() when hasMore is false', async () => {
      const vuetify = createVuetify({ components, directives })
      const w = mount(ScansResults, { global: { plugins: [i18n, vuetify] } })
      await vi.advanceTimersByTimeAsync(500); await flushPromises()
      const s = useScansStore()
      s.hasMore = false
      const spy = vi.spyOn(s, 'loadMore').mockResolvedValue()
      observeCb!([{ isIntersecting: true }])
      expect(spy).not.toHaveBeenCalled()
      w.unmount()
    })
  })
})
