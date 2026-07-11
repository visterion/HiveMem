import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
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

  describe('infinite scroll (H8)', () => {
    let observeCb: ((entries: { isIntersecting: boolean }[]) => void) | null = null
    let originalIO: any

    beforeEach(() => {
      originalIO = (globalThis as any).IntersectionObserver
      ;(globalThis as any).IntersectionObserver = class {
        constructor(cb: (entries: { isIntersecting: boolean }[]) => void) { observeCb = cb }
        observe() {}
        unobserve() {}
        disconnect() {}
      }
    })
    afterEach(() => { (globalThis as any).IntersectionObserver = originalIO; observeCb = null })

    it('calls media.loadMore() when the sentinel intersects and hasMore is true', async () => {
      const w = mount(PhotosRoute, globalOpts)
      const media = useMediaStore()
      for (let i = 0; i < 40 && !media.loaded; i++) await new Promise(r => setTimeout(r, 20))
      await flushPromises()
      media.hasMore = true
      const spy = vi.spyOn(media, 'loadMore').mockResolvedValue()
      observeCb!([{ isIntersecting: true }])
      expect(spy).toHaveBeenCalled()
      w.unmount()
    })

    it('does not call media.loadMore() when hasMore is false', async () => {
      const w = mount(PhotosRoute, globalOpts)
      const media = useMediaStore()
      for (let i = 0; i < 40 && !media.loaded; i++) await new Promise(r => setTimeout(r, 20))
      await flushPromises()
      media.hasMore = false
      const spy = vi.spyOn(media, 'loadMore').mockResolvedValue()
      observeCb!([{ isIntersecting: true }])
      expect(spy).not.toHaveBeenCalled()
      w.unmount()
    })
  })

  it('renders a localized label for the "older" bucket, not the raw key (E6)', async () => {
    const w = mount(PhotosRoute, globalOpts)
    const media = useMediaStore()
    for (let i = 0; i < 40 && !media.loaded; i++) await new Promise(r => setTimeout(r, 20))
    // Force a photo with no taken_at/created_at into the group list, landing in
    // the 'older' bucket (bucketKeyFor returns 'older' for a missing date).
    media.photos = [...media.photos, {
      cell_id: 'no-date', attachment_id: 'att-no-date', realm: 'private', summary: null,
      tags: [], mime_type: 'image/jpeg', size_bytes: null, created_at: null, taken_at: null,
      width: null, height: null, camera_make: null, camera_model: null,
      gps_lat: null, gps_lon: null, place_name: null, thumbnail_uri: null, content_uri: null,
    }]
    await flushPromises()
    expect(w.text()).not.toContain('older') // raw i18n key must not leak into the UI
    expect(w.findAll('.photo-date').some(d => d.text().length > 0 && d.text() !== 'older')).toBe(true)
  })
})
