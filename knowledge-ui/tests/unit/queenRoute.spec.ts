import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h } from 'vue'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import { resetApi } from '../../src/api/useApi'
import QueenRoute from '../../src/pages/QueenRoute.vue'
import { i18n } from '../../src/i18n'

// VNavigationDrawer needs layout context — wrap in VApp to satisfy it
const { VApp } = components

describe('QueenRoute', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'de'
    setActivePinia(createPinia())
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
    // Use fake timers to control the mock client's latency setTimeout calls
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders the run feed and pending proposals from the mock client', async () => {
    const vuetify = createVuetify({ components, directives })
    const AppWrapper = defineComponent({
      render: () => h(VApp, () => h(QueenRoute)),
    })
    const wrapper = mount(AppWrapper, { global: { plugins: [vuetify, i18n] } })
    // Advance fake timers past the mock client's latency (up to 200 ms)
    await vi.advanceTimersByTimeAsync(300)
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('Queen-Aktivität')
    expect(text).toContain('isolated-cell-bee')
    expect(text).toContain('Offene Vorschläge')
  })
})
