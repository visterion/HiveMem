import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'
import Lightbox from '../../src/components/media/Lightbox.vue'
import { useCanvasStore } from '../../src/stores/canvas'
import { i18n } from '../../src/i18n'

const withGps = {
  cell_id: 'media-ph1', attachment_id: 'att-ph1', realm: 'private', summary: 'Foto 1',
  tags: ['holiday'], mime_type: 'image/jpeg', size_bytes: 3_100_000,
  created_at: '2026-06-07T00:00:00Z', taken_at: '2026-06-07T00:00:00Z',
  width: 4032, height: 3024, camera_make: 'Apple', camera_model: 'iPhone 16 Pro',
  gps_lat: 49.4874, gps_lon: 8.4660, place_name: 'Mannheim, DE',
  thumbnail_uri: null, content_uri: null,
}
const noExif = { ...withGps, cell_id: 'media-ph2', attachment_id: 'att-ph2', summary: null,
  taken_at: null, camera_make: null, camera_model: null, gps_lat: null, gps_lon: null, place_name: null }

function makeRouter() {
  const Blank = defineComponent({ render: () => h('div') })
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'search', component: Blank },
      { path: '/graph', name: 'graph', component: Blank },
    ],
  })
}

function mountLb(item: any, router?: Router) {
  setActivePinia(createPinia())
  const global: any = { plugins: [i18n], stubs: { HmIcon: true, 'router-link': true } }
  if (router) global.plugins.push(router)
  return mount(Lightbox, { props: { item }, global })
}

describe('Lightbox', () => {
  it('shows camera, resolution, place, and an OSM link when GPS is present', () => {
    const w = mountLb(withGps)
    const txt = w.text()
    expect(txt).toContain('Apple iPhone 16 Pro')
    expect(txt).toContain('4032 × 3024')
    expect(txt).toContain('Mannheim, DE')
    const osm = w.find('a.osm-link')
    expect(osm.exists()).toBe(true)
    expect(osm.attributes('href')).toContain('openstreetmap.org')
    expect(osm.attributes('href')).toContain('49.4874')
  })

  it('renders "—" for missing EXIF and no OSM link', () => {
    const w = mountLb(noExif)
    expect(w.text()).toContain('—')
    expect(w.find('a.osm-link').exists()).toBe(false)
  })

  it('emits close / next / prev from the controls', async () => {
    const w = mountLb(withGps)
    await w.find('[data-testid="lb-close"]').trigger('click')
    await w.find('[data-testid="lb-next"]').trigger('click')
    await w.find('[data-testid="lb-prev"]').trigger('click')
    expect(w.emitted('close')).toBeTruthy()
    expect(w.emitted('next')).toBeTruthy()
    expect(w.emitted('prev')).toBeTruthy()
  })

  it('"show in graph" navigates to the graph route and focuses the cell', async () => {
    const router = makeRouter()
    router.push('/'); await router.isReady()
    const w = mountLb(withGps, router)
    const canvas = useCanvasStore()
    await w.find('[data-testid="lb-graph"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('graph')
    expect(canvas.focusedId).toBe('media-ph1')
  })

  it('resets imgFailed when navigating to another gallery image sharing the same cell_id (E6)', async () => {
    // Two gallery images belonging to the same cell (attachment_id differs,
    // cell_id doesn't) — before this fix, imgFailed was keyed on cell_id, so it
    // never reset navigating between them.
    const sameCellOther = { ...withGps, attachment_id: 'att-ph1-other' }
    const w = mountLb(withGps)
    await w.find('img').trigger('error')
    expect(w.find('img').classes()).toContain('hidden')

    await w.setProps({ item: sameCellOther })
    expect(w.find('img').classes()).not.toContain('hidden')
  })
})
