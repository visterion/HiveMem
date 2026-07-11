import { describe, expect, it, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { defineComponent, h } from 'vue'
import TopBar from '../../src/components/shell/TopBar.vue'
import { i18n } from '../../src/i18n'
import { usePrefsStore } from '../../src/stores/prefs'

function stubMatchMedia(matches: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches, media: query, addEventListener: () => {}, removeEventListener: () => {},
    addListener: () => {}, removeListener: () => {}, onchange: null, dispatchEvent: () => false,
  }))
}

const Blank = defineComponent({ render: () => h('div') })
const router = createRouter({ history: createMemoryHistory(),
  routes: [
    { path: '/scans', name: 'scans', component: Blank, meta: { title: 'nav.scans' } },
    { path: '/settings', name: 'settings', component: Blank, meta: { title: 'nav.settings' } },
  ] })

describe('TopBar', () => {
  beforeEach(() => { setActivePinia(createPinia()); i18n.global.locale.value = 'de'; localStorage.clear(); vi.unstubAllGlobals() })

  it('shows the route title and toggles theme + language', async () => {
    stubMatchMedia(false)
    await router.push('/scans'); await router.isReady()
    const prefs = usePrefsStore(); prefs.init()
    const w = mount(TopBar, { global: { plugins: [router, i18n] } })
    await flushPromises()
    expect(w.text()).toContain('Scans')

    await w.find('[data-test="theme-light"]').trigger('click')
    expect(prefs.theme).toBe('light')

    await w.find('[data-test="lang-en"]').trigger('click')
    expect(i18n.global.locale.value).toBe('en')
  })

  it('shows settings gear on mobile and navigates to /settings', async () => {
    stubMatchMedia(true)
    await router.push('/scans'); await router.isReady()
    const w = mount(TopBar, { global: { plugins: [router, i18n] } })
    await flushPromises()

    const gear = w.find('[data-test="tb-settings"]')
    expect(gear.exists()).toBe(true)
    await gear.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('settings')
  })

  it('marks the breadcrumb root segment and separator so the mobile hide rule matches both', async () => {
    // The mobile media rule is `.crumbs > .root, .crumbs > .sep { display:none }`.
    // The test DOM does not evaluate media queries, so pin the selector targets:
    // both marker classes must exist as direct children of .crumbs, and the
    // page-title <b> must not carry either (it has to stay visible).
    stubMatchMedia(true)
    await router.push('/scans'); await router.isReady()
    const w = mount(TopBar, { global: { plugins: [router, i18n] } })
    await flushPromises()

    expect(w.find('.crumbs > .root').exists()).toBe(true)
    expect(w.find('.crumbs > .sep').exists()).toBe(true)
    const title = w.find('.crumbs > b')
    expect(title.exists()).toBe(true)
    expect(title.classes()).not.toContain('root')
    expect(title.classes()).not.toContain('sep')
  })

  it('hides settings gear on desktop', async () => {
    stubMatchMedia(false)
    await router.push('/scans'); await router.isReady()
    const w = mount(TopBar, { global: { plugins: [router, i18n] } })
    await flushPromises()

    expect(w.find('[data-test="tb-settings"]').exists()).toBe(false)
  })
})
