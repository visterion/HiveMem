import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
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
})
