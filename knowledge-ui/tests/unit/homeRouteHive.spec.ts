import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h } from 'vue'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import HomeRoute from '../../src/pages/HomeRoute.vue'
import { i18n } from '../../src/i18n'

describe('HomeRoute (/hive stage)', () => {
  beforeEach(() => { setActivePinia(createPinia()); i18n.global.locale.value = 'de'; localStorage.setItem('hivemem_mock','true') })

  it('no longer renders the shell chrome (rail moved to AppShell)', () => {
    const vuetify = createVuetify({ components, directives })
    const Wrapper = defineComponent({ render: () => h(components.VApp, () => h(HomeRoute)) })
    const w = mount(Wrapper, { global: { plugins: [vuetify, i18n], stubs: { SphereCanvas: true, ScanPanel: true, Reader: true } } })
    expect(w.find('.rail').exists()).toBe(false)
  })
})
