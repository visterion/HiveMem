import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import RealmsPanel from '../../src/components/realms/RealmsPanel.vue'
import { i18n } from '../../src/i18n'
import { useRealmsStore } from '../../src/stores/realms'

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'search', component: { template: '<div/>' } },
      { path: '/realms', name: 'realms', component: { template: '<div/>' } },
    ],
  })
}

describe('RealmsPanel', () => {
  beforeEach(() => { setActivePinia(createPinia()); i18n.global.locale.value = 'de'; vi.useFakeTimers() })
  afterEach(() => vi.useRealTimers())

  it('renders one row per realm with correct lock/cloud icon', async () => {
    const s = useRealmsStore()
    // seed directly so onMounted's loadRealms skips (loaded guard)
    s.realms = [
      { id: 'private', count: 61, priv: 'local', desc: '', color: 'var(--r-private)' },
      { id: 'work', count: 112, priv: 'cloud', desc: '', color: 'var(--r-work)' },
    ]
    s.loaded = true

    const router = makeRouter()
    router.push('/realms'); await router.isReady()
    const w = mount(RealmsPanel, { global: { plugins: [router, i18n] } })
    await flushPromises()

    expect(w.findAll('.realm-li').length).toBe(2)
    const html = w.html()
    expect(html).toContain('x="5"') // lock icon (local realm)
    expect(html).toContain('M7 18a4 4 0 0 1 0-8') // cloud icon (cloud realm)
  })

  it('drills into /?realm=<id> on click', async () => {
    const s = useRealmsStore()
    s.realms = [{ id: 'work', count: 112, priv: 'cloud', desc: '', color: 'var(--r-work)' }]
    s.loaded = true
    const router = makeRouter()
    const spy = vi.spyOn(router, 'push')
    router.push('/realms'); await router.isReady()
    const w = mount(RealmsPanel, { global: { plugins: [router, i18n] } })
    await flushPromises()

    await w.find('.realm-li').trigger('click')
    expect(spy).toHaveBeenCalledWith({ path: '/', query: { realm: 'work' } })
  })
})
