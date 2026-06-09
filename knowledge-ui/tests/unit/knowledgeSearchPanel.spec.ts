import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import SearchPanel from '../../src/components/knowledge/SearchPanel.vue'
import { i18n } from '../../src/i18n'
import { resetApi } from '../../src/api/useApi'
import { useCellStore } from '../../src/stores/cell'

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
    const w = mount(SearchPanel, { global: { plugins: [vuetify, i18n] } })
    await w.find('input').setValue('a')
    await vi.advanceTimersByTimeAsync(500) // debounce 180ms + mock latency <=200ms
    await flushPromises()
    const rows = w.findAll('.row')
    expect(rows.length).toBeGreaterThan(0)
    expect(w.find('.row svg').exists()).toBe(true) // ScoreRing
    await rows[0].trigger('click')
    await vi.advanceTimersByTimeAsync(500)
    await flushPromises()
    expect(useCellStore().currentId).toBeTruthy()
  })
})
