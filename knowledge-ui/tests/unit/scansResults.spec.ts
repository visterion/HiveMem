import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import ScansResults from '../../src/components/scans/ScansResults.vue'
import { i18n } from '../../src/i18n/index'
import { resetApi } from '../../src/api/useApi'
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
})
