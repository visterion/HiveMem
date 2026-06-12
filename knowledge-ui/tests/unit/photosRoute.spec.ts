import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import PhotosRoute from '../../src/pages/PhotosRoute.vue'
import { resetApi } from '../../src/api/useApi'
import { useMediaStore } from '../../src/stores/media'
import { i18n } from '../../src/i18n'

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'search', component: { template: '<div/>' } },
      { path: '/graph', name: 'graph', component: { template: '<div/>' } },
    ],
  })
}

const globalOpts = { global: { plugins: [i18n, makeRouter()], stubs: { HmIcon: true, 'router-link': true } } }

describe('PhotosRoute', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
    // jsdom lacks ResizeObserver
    ;(globalThis as any).ResizeObserver = class { observe(){} unobserve(){} disconnect(){} }
  })

  it('loads photos and renders date-group sections with tiles', async () => {
    const w = mount(PhotosRoute, globalOpts)
    const media = useMediaStore()
    // mock client uses a 50–200ms latency; wait for the load to settle
    for (let i = 0; i < 40 && !media.loaded; i++) await new Promise(r => setTimeout(r, 20))
    await flushPromises()
    expect(w.findAll('.photo-group').length).toBeGreaterThan(0)
    expect(w.findAll('button.photo').length).toBeGreaterThan(5)
  })
})
