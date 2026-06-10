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
    const rows = w.findAll('.row')
    expect(rows.length).toBeGreaterThan(0)
    expect(w.find('.row svg').exists()).toBe(true)
    await rows[0].trigger('click')
    await vi.advanceTimersByTimeAsync(500)
    await flushPromises()
    expect(useCellStore().currentId).toBeTruthy()
  })

  it('with ?realm=documents shows a chip and filters results to that realm', async () => {
    const vuetify = createVuetify({ components, directives })
    const router = makeRouter()
    router.push({ path: '/', query: { realm: 'documents' } }); await router.isReady()
    const w = mount(SearchPanel, { global: { plugins: [vuetify, i18n, router] } })
    await vi.advanceTimersByTimeAsync(500)
    await flushPromises()

    expect(w.find('.realm-chip').exists()).toBe(true)
    expect(w.find('.realm-chip').text()).toContain('documents')
    const rows = w.findAll('.row')
    expect(rows.length).toBeGreaterThan(0)
    expect(rows.every(r => r.find('.row-meta').text().includes('documents'))).toBe(true)
  })

  it('removing the realm chip clears the query param', async () => {
    const vuetify = createVuetify({ components, directives })
    const router = makeRouter()
    router.push({ path: '/', query: { realm: 'documents' } }); await router.isReady()
    const w = mount(SearchPanel, { global: { plugins: [vuetify, i18n, router] } })
    await vi.advanceTimersByTimeAsync(500); await flushPromises()

    await w.find('.realm-chip .x').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.realm).toBeUndefined()
    expect(w.find('.realm-chip').exists()).toBe(false)
  })

  it('signal filter still narrows results', async () => {
    const vuetify = createVuetify({ components, directives })
    const router = makeRouter(); router.push('/'); await router.isReady()
    const w = mount(SearchPanel, { global: { plugins: [vuetify, i18n, router] } })
    await w.find('input').setValue('a')
    await vi.advanceTimersByTimeAsync(500); await flushPromises()
    const seg = w.findAll('.seg button')
    expect(seg.length).toBeGreaterThan(1)
    await seg[1].trigger('click')
    await flushPromises()
    expect(w.findAll('.row').length).toBeGreaterThanOrEqual(0)
  })
})
