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

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'search', component: Stage, meta: { title: 'nav.search', full: false } },
      { path: '/upload', name: 'upload', component: Stage, meta: { title: 'nav.upload', full: true } },
    ],
  })
}

// The FAB is an upload affordance; it must not float over unrelated routes.
describe('AppShell upload FAB gating', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders the upload FAB only on the upload route', async () => {
    const router = makeRouter()
    router.push('/')
    await router.isReady()
    const vuetify = createVuetify({ components, directives })
    const w = mount(AppShell, { global: { plugins: [router, i18n, vuetify] } })
    await flushPromises()
    expect(w.findComponent({ name: 'UploadFab' }).exists()).toBe(false)

    await router.push('/upload')
    await flushPromises()
    expect(w.findComponent({ name: 'UploadFab' }).exists()).toBe(true)
  })
})
