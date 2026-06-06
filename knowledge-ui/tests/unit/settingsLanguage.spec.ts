import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h } from 'vue'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import SettingsPanel from '../../src/components/shell/SettingsPanel.vue'
import { i18n, STORAGE_KEY } from '../../src/i18n'

const { VApp } = components

function mountPanel() {
  const vuetify = createVuetify({ components, directives })
  const Wrapper = defineComponent({ render: () => h(VApp, () => h(SettingsPanel)) })
  return mount(Wrapper, { global: { plugins: [vuetify, i18n] } })
}

describe('SettingsPanel language switcher', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    i18n.global.locale.value = 'de'
  })

  it('renders German strings by default', () => {
    const text = mountPanel().text()
    expect(text).toContain('Abmelden')
    expect(text).toContain('Mock-Modus')
    expect(text).toContain('Sprache')
  })

  it('renders English strings when locale is en', () => {
    i18n.global.locale.value = 'en'
    const text = mountPanel().text()
    expect(text).toContain('Log out')
    expect(text).toContain('Mock mode')
  })

  it('switching to EN updates the locale and persists it', async () => {
    const wrapper = mountPanel()
    const enBtn = wrapper.findAll('button').find(b => b.text() === 'EN')
    expect(enBtn).toBeTruthy()
    await enBtn!.trigger('click')
    expect(i18n.global.locale.value).toBe('en')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('en')
  })
})
