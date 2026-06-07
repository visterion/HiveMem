import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import SettingsRoute from '../../src/pages/SettingsRoute.vue'
import { i18n } from '../../src/i18n'

describe('SettingsRoute', () => {
  it('renders the sovereignty/routing toggle rows', () => {
    i18n.global.locale.value = 'de'
    const vuetify = createVuetify({ components, directives })
    const Wrapper = defineComponent({ render: () => h(components.VApp, () => h(SettingsRoute)) })
    const w = mount(Wrapper, { global: { plugins: [vuetify, i18n] } })
    expect(w.text()).toContain('Lokale Modelle erzwingen')
    expect(w.text()).toContain('OCR')
    expect(w.findAll('.switch').length).toBeGreaterThanOrEqual(5)
  })
})
