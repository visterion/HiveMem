import { describe, expect, it, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { defineComponent, h } from 'vue'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import AppShell from '../../src/components/shell/AppShell.vue'
import { i18n } from '../../src/i18n'

const Stage = defineComponent({ render: () => h('div', { class: 'routed' }, 'STAGE') })
function makeRouter(full: boolean) {
  return createRouter({ history: createMemoryHistory(),
    routes: [{ path: '/', name: 'search', component: Stage, meta: { title: 'nav.search', full } }] })
}

describe('AppShell', () => {
  beforeEach(() => { setActivePinia(createPinia()); i18n.global.locale.value = 'de' })

  it('renders rail, topbar and the routed stage; applies full span from meta', async () => {
    const router = makeRouter(true); router.push('/'); await router.isReady()
    const vuetify = createVuetify({ components, directives })
    const w = mount(AppShell, { global: { plugins: [router, i18n, vuetify] } })
    await flushPromises()
    expect(w.find('.rail').exists()).toBe(true)
    expect(w.find('.topbar').exists()).toBe(true)
    expect(w.find('.routed').text()).toBe('STAGE')
    expect(w.find('.stage').classes()).toContain('full')
  })

  it('renders named panel + inspector components for a route that declares them', async () => {
    const Panel = defineComponent({ render: () => h('div', { class: 'mock-panel' }, 'PANEL') })
    const Insp = defineComponent({ render: () => h('div', { class: 'mock-insp' }, 'INSP') })
    const router = createRouter({ history: createMemoryHistory(),
      routes: [{ path: '/', name: 'search', components: { default: Stage, panel: Panel, inspector: Insp }, meta: { title: 'nav.search', full: false } }] })
    router.push('/'); await router.isReady()
    const vuetify = createVuetify({ components, directives })
    const w = mount(AppShell, { global: { plugins: [router, i18n, vuetify] } })
    await flushPromises()
    expect(w.find('.mock-panel').exists()).toBe(true)
    expect(w.find('.mock-insp').exists()).toBe(true)
    expect(w.find('.routed').exists()).toBe(true) // default stage still renders
  })
})
