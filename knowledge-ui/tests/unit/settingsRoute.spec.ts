import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import SettingsRoute from '../../src/pages/SettingsRoute.vue'
import { useAuthStore } from '../../src/stores/auth'
import { i18n } from '../../src/i18n'

let pinia: Pinia
const opts = () => ({ global: { plugins: [i18n, pinia] } })

describe('SettingsRoute', () => {
  beforeEach(() => {
    pinia = createPinia(); setActivePinia(pinia)
    i18n.global.locale.value = 'de'
    localStorage.clear()
  })

  it('renders the account section (signed-in/role) and German routing labels, no Vuetify', () => {
    const auth = useAuthStore(); auth.identity = 'tester'; auth.role = 'admin' as any
    const w = mount(SettingsRoute, opts())
    const text = w.text()
    expect(text).toContain('tester')
    expect(text).toContain('admin')
    expect(text).toContain('Souveränität & Routing')
    expect(text).toContain('Lokale Modelle erzwingen')
    expect(w.html()).not.toContain('v-')
  })

  it('routing items are read-only status badges (no interactive switch beyond mock-mode)', () => {
    const w = mount(SettingsRoute, opts())
    expect(w.findAll('.status-badge').length).toBe(6)
    expect(w.findAll('.switch').length).toBe(1)
  })

  it('language buttons switch the locale (folds in settingsLanguage coverage)', async () => {
    const w = mount(SettingsRoute, opts())
    expect(w.text()).toContain('Souveränität & Routing')
    await w.find('[data-test="lang-en"]').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('Sovereignty & Routing')
  })

  it('logout button calls auth.logout', async () => {
    const auth = useAuthStore()
    const spy = vi.spyOn(auth, 'logout').mockResolvedValue(undefined as unknown as void)
    const w = mount(SettingsRoute, opts())
    await w.find('[data-test="logout"]').trigger('click')
    expect(spy).toHaveBeenCalled()
  })
})
