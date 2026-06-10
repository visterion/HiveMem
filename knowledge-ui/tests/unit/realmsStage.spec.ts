import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import RealmsStage from '../../src/components/realms/RealmsStage.vue'
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

function seed() {
  const s = useRealmsStore()
  s.realms = [
    { id: 'work', count: 80, priv: 'cloud', desc: 'Projekte', color: 'var(--r-work)' },
    { id: 'private', count: 40, priv: 'local', desc: 'Familie', color: 'var(--r-private)' },
  ]
  s.loaded = true
  return s
}

describe('RealmsStage', () => {
  beforeEach(() => { setActivePinia(createPinia()); i18n.global.locale.value = 'de'; vi.useFakeTimers() })
  afterEach(() => vi.useRealTimers())

  it('renders one card per realm with proportional spark widths', async () => {
    seed()
    const router = makeRouter(); router.push('/realms'); await router.isReady()
    const w = mount(RealmsStage, { global: { plugins: [router, i18n] } })
    await flushPromises()

    const cards = w.findAll('.realm-card')
    expect(cards.length).toBe(2)
    // footer shows the count once, not duplicated ("80 Zellen", never "80 80")
    expect(cards[0].find('.rc-foot').text()).toContain('80 Zellen')
    expect(cards[0].find('.rc-foot').text()).not.toMatch(/80\s+80/)
    const sparks = w.findAll('.rc-spark span')
    expect(sparks[0].attributes('style')).toContain('width: 100%') // max count → full
    expect(sparks[1].attributes('style')).toContain('width: 50%')  // 40/80
  })

  it('drills into /?realm=<id> on card click', async () => {
    seed()
    const router = makeRouter(); const spy = vi.spyOn(router, 'push')
    router.push('/realms'); await router.isReady()
    const w = mount(RealmsStage, { global: { plugins: [router, i18n] } })
    await flushPromises()

    await w.findAll('.realm-card')[0].trigger('click')
    expect(spy).toHaveBeenCalledWith({ path: '/', query: { realm: 'work' } })
  })
})
