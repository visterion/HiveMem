import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ScansPanel from '../../src/components/scans/ScansPanel.vue'
import { i18n } from '../../src/i18n'
import { useScansStore } from '../../src/stores/scans'

describe('ScansPanel', () => {
  beforeEach(() => { setActivePinia(createPinia()); i18n.global.locale.value = 'de' })

  it('renders saved-views row + facet groups with counts; clicking a facet calls toggleFacet', async () => {
    const s = useScansStore()
    s.facetCounts = {
      tag: [{value:'contract',count:5},{value:'invoice',count:2}],
      status: [{value:'committed',count:6},{value:'pending',count:1}],
      realm: [{value:'documents',count:7}], year: [{value:'2025',count:5}], signal: [],
    } as any
    s.savedViews = [{ id:'v-steuer', name:'Steuer 2025', filter:{ tag:['contract'] } }] as any
    const w = mount(ScansPanel, { global: { plugins: [i18n] } })
    // saved views: "all docs" + the one saved view
    expect(w.findAll('.sv-row').length).toBeGreaterThanOrEqual(2)
    expect(w.text()).toContain('Steuer 2025')
    // facet groups rendered with a count
    expect(w.text()).toContain('contract'); expect(w.text()).toContain('5')
    // click first facet row → toggleFacet called (facet set changes)
    await w.findAll('.facet-row')[0].trigger('click')
    expect(s.activeCount).toBeGreaterThan(0)
  })

  it('renders correspondent facet group from facetCounts.correspondent', async () => {
    const s = useScansStore()
    s.facetCounts = {
      correspondent: [{value:'Finanzamt Berlin',count:3},{value:'Stadtwerke',count:1}],
    } as any
    const w = mount(ScansPanel, { global: { plugins: [i18n] } })
    expect(w.text()).toContain('Finanzamt Berlin')
    expect(w.text()).toContain('3')
  })

  it('clicking correspondent facet toggles store.facets.correspondent', async () => {
    const s = useScansStore()
    s.facetCounts = {
      correspondent: [{value:'Finanzamt',count:2}],
    } as any
    const w = mount(ScansPanel, { global: { plugins: [i18n] } })
    await w.findAll('.facet-row').at(-1)!.trigger('click')
    expect(s.facets.correspondent.size).toBeGreaterThan(0)
  })

  it('saveView button calls store.saveView when prompted with a name', async () => {
    const s = useScansStore()
    const saveSpy = vi.spyOn(s, 'saveView').mockResolvedValue()
    vi.stubGlobal('prompt', () => 'Meine Ansicht')
    const w = mount(ScansPanel, { global: { plugins: [i18n] } })
    await w.find('.sv-save-btn').trigger('click')
    await flushPromises()
    expect(saveSpy).toHaveBeenCalledWith('Meine Ansicht', expect.any(Object))
    vi.unstubAllGlobals()
  })

  it('deleteView button calls store.deleteView with the view id', async () => {
    const s = useScansStore()
    s.savedViews = [{ id:'v-del', name:'Del Me', filter:{} }] as any
    const delSpy = vi.spyOn(s, 'deleteView').mockResolvedValue()
    const w = mount(ScansPanel, { global: { plugins: [i18n] } })
    await w.find('.sv-del').trigger('click')
    await flushPromises()
    expect(delSpy).toHaveBeenCalledWith('v-del')
  })
})
