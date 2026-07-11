import { describe, expect, it } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { defineComponent, h } from 'vue'
import IconRail from '../../src/components/shell/IconRail.vue'
import { i18n } from '../../src/i18n'
import { useAuthStore } from '../../src/stores/auth'

function makeRouter() {
  const Blank = defineComponent({ render: () => h('div') })
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'search', component: Blank, meta: { title: 'nav.search', icon: 'search' } },
      { path: '/scans', name: 'scans', component: Blank, meta: { title: 'nav.scans', icon: 'scans' } },
      { path: '/queen', name: 'queen', component: Blank, meta: { title: 'nav.queen', icon: 'queen', role: 'admin' } },
    ],
  })
}

describe('IconRail', () => {
  it('marks the current route active and hides admin entries for non-admins', async () => {
    setActivePinia(createPinia())
    i18n.global.locale.value = 'de'
    const auth = useAuthStore(); auth.role = 'reader' as any
    const router = makeRouter()
    router.push('/scans'); await router.isReady()
    const w = mount(IconRail, { global: { plugins: [router, i18n] } })
    await flushPromises()
    expect(w.find('.rail-btn.active').exists()).toBe(true)
    expect(w.find('.rail-btn.active').text()).toContain('Scans')
    expect(w.text()).not.toContain('Queen') // admin-only hidden for reader
  })

  it('shows admin entries for admins', async () => {
    setActivePinia(createPinia())
    const auth = useAuthStore(); auth.role = 'admin' as any
    const router = makeRouter(); router.push('/'); await router.isReady()
    const w = mount(IconRail, { global: { plugins: [router, i18n] } })
    await flushPromises()
    expect(w.text()).toContain('Queen')
  })

  it('marks the settings button as desktop-only (hidden on mobile via CSS)', async () => {
    setActivePinia(createPinia())
    const auth = useAuthStore(); auth.role = 'admin' as any
    const router = makeRouter(); router.push('/'); await router.isReady()
    const w = mount(IconRail, { global: { plugins: [router, i18n] } })
    await flushPromises()
    const buttons = w.findAll('.rail-btn')
    const settingsBtn = buttons.find(b => b.text().includes('Einstellungen'))
    expect(settingsBtn?.classes()).toContain('rail-btn--desktop-only')
    const queenBtn = buttons.find(b => b.text().includes('Queen'))
    expect(queenBtn?.classes()).not.toContain('rail-btn--desktop-only')
  })
})
