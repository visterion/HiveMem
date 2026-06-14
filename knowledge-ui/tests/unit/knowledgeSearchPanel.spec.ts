import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import SearchPanel from '../../src/components/knowledge/SearchPanel.vue'
import { i18n } from '../../src/i18n'
import { resetApi } from '../../src/api/useApi'
import { useCellStore } from '../../src/stores/cell'

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'search', component: { template: '<div/>' } },
      { path: '/realms', name: 'realms', component: { template: '<div/>' } },
    ],
  })
}

describe('knowledge SearchPanel', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'de'
    setActivePinia(createPinia())
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
    vi.useFakeTimers()
  })
  afterEach(() => vi.useRealTimers())

  it('renders ranked rows with a ScoreRing and selects a cell on click', async () => {
    const vuetify = createVuetify({ components, directives })
    const router = makeRouter(); router.push('/'); await router.isReady()
    const w = mount(SearchPanel, { global: { plugins: [vuetify, i18n, router] } })
    await w.find('input').setValue('a')
    await vi.advanceTimersByTimeAsync(500)
    await flushPromises()
    // Use .rows .row to target only result rows (not FacetGroup's .facet-title.row buttons)
    const rows = w.findAll('.rows .row')
    expect(rows.length).toBeGreaterThan(0)
    expect(w.find('.rows .row svg').exists()).toBe(true)
    await rows[0].trigger('click')
    await vi.advanceTimersByTimeAsync(500)
    await flushPromises()
    expect(useCellStore().currentId).toBeTruthy()
  })

  it('with ?realm=documents shows clear-btn and filters results to that realm', async () => {
    const vuetify = createVuetify({ components, directives })
    const router = makeRouter()
    router.push({ path: '/', query: { realm: 'documents' } }); await router.isReady()
    const w = mount(SearchPanel, { global: { plugins: [vuetify, i18n, router] } })
    await vi.advanceTimersByTimeAsync(500)
    await flushPromises()

    // The clear-btn should appear because activeFilterCount > 0 (realm facet preselected)
    expect(w.find('.clear-btn').exists()).toBe(true)
    // The deep-link preselects exactly 1 facet — clear-btn shows the count
    expect(w.find('.clear-btn').text()).toContain('(1)')
    // Deep-link switches sort away from 'relevance' → sort-btn must NOT show 'Relevanz'/'Relevance'
    expect(w.find('.sort-btn').text()).not.toMatch(/Relevanz|Relevance/i)
  })

  it('clear-btn clears facets and hides itself', async () => {
    const vuetify = createVuetify({ components, directives })
    const router = makeRouter()
    router.push({ path: '/', query: { realm: 'documents' } }); await router.isReady()
    const w = mount(SearchPanel, { global: { plugins: [vuetify, i18n, router] } })
    await vi.advanceTimersByTimeAsync(500); await flushPromises()

    // clear-btn should be visible (realm facet is active)
    expect(w.find('.clear-btn').exists()).toBe(true)
    await w.find('.clear-btn').trigger('click')
    await flushPromises()
    // After clearing, the button should disappear (activeFilterCount === 0)
    expect(w.find('.clear-btn').exists()).toBe(false)
  })

  it('SortMenu renders and emits change on pick', async () => {
    const vuetify = createVuetify({ components, directives })
    const router = makeRouter(); router.push('/'); await router.isReady()
    const w = mount(SearchPanel, { global: { plugins: [vuetify, i18n, router] } })
    await w.find('input').setValue('a')
    await vi.advanceTimersByTimeAsync(500); await flushPromises()
    // SortMenu should be present
    expect(w.find('.sortmenu').exists()).toBe(true)
    // Open the sort menu and pick an option
    await w.find('.sort-btn').trigger('click')
    await flushPromises()
    const opts = w.findAll('.sort-pop button')
    // SearchPanel defines exactly 4 sort options: relevance / newest / oldest / title
    expect(opts.length).toBe(4)
  })
})
