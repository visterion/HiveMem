import { describe, expect, it, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { defineComponent, h } from 'vue'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import AppShell from '../../src/components/shell/AppShell.vue'
import IconRail from '../../src/components/shell/IconRail.vue'
import { i18n } from '../../src/i18n'
import { useUiStore } from '../../src/stores/ui'
import { useAuthStore } from '../../src/stores/auth'

function stubMatchMedia(matches: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches, media: query, addEventListener: () => {}, removeEventListener: () => {},
    addListener: () => {}, removeListener: () => {}, onchange: null, dispatchEvent: () => false,
  }))
}

const Blank = defineComponent({ render: () => h('div') })
function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'search', component: Blank, meta: { title: 'nav.search', icon: 'search', full: false } },
      { path: '/scans', name: 'scans', component: Blank, meta: { title: 'nav.scans', icon: 'scans', full: false } },
    ],
  })
}

describe('AppShell drawer behavior', () => {
  beforeEach(() => { setActivePinia(createPinia()); i18n.global.locale.value = 'de'; vi.unstubAllGlobals() })

  it('drawer stays closed after route change to / on mobile', async () => {
    stubMatchMedia(true)
    const router = makeRouter()
    const ui = useUiStore()
    ui.setDrawer(true)
    await router.push('/scans'); await router.isReady()
    const vuetify = createVuetify({ components, directives })
    mount(AppShell, { global: { plugins: [router, i18n, vuetify] } })
    await flushPromises()
    await router.push('/')
    await flushPromises()
    expect(ui.mobileDrawerOpen).toBe(false)
  })

  it('re-tapping active search tab toggles the drawer', async () => {
    stubMatchMedia(true)
    const router = makeRouter()
    const auth = useAuthStore(); auth.role = 'admin' as any
    const ui = useUiStore()
    await router.push('/'); await router.isReady()
    const w = mount(IconRail, { global: { plugins: [router, i18n] } })
    await flushPromises()
    expect(ui.mobileDrawerOpen).toBe(false)
    const railButton = () => w.findAll('.rail-btn').find(b => b.classes().includes('active'))!
    await railButton().trigger('click')
    expect(ui.mobileDrawerOpen).toBe(true)
    await railButton().trigger('click')
    expect(ui.mobileDrawerOpen).toBe(false)
  })
})
