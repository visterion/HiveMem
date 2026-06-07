import { describe, expect, it, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { defineComponent, h } from 'vue'
import TopBar from '../../src/components/shell/TopBar.vue'
import { i18n } from '../../src/i18n'
import { usePrefsStore } from '../../src/stores/prefs'

const Blank = defineComponent({ render: () => h('div') })
const router = createRouter({ history: createMemoryHistory(),
  routes: [{ path: '/scans', name: 'scans', component: Blank, meta: { title: 'nav.scans' } }] })

describe('TopBar', () => {
  beforeEach(() => { setActivePinia(createPinia()); i18n.global.locale.value = 'de'; localStorage.clear() })

  it('shows the route title and toggles theme + language', async () => {
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
})
