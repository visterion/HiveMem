import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useMediaStore } from '../../src/stores/media'
import { resetApi } from '../../src/api/useApi'

describe('media store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
  })

  it('load() populates photos and builds groups', async () => {
    const s = useMediaStore()
    await s.load()
    expect(s.photos.length).toBeGreaterThan(5)
    expect(s.loaded).toBe(true)
    expect(s.error).toBeNull()
    expect(s.groups.length).toBeGreaterThan(0)
    expect(s.groups[0].items.length).toBeGreaterThan(0)
  })

  it('lightbox open/next/prev wrap around and close resets', async () => {
    const s = useMediaStore()
    await s.load()
    const n = s.photos.length
    s.openLightbox(0)
    expect(s.lightboxItem?.cell_id).toBe(s.photos[0].cell_id)
    s.prev()
    expect(s.lightboxIndex).toBe(n - 1)   // wrapped to last
    s.next()
    expect(s.lightboxIndex).toBe(0)        // wrapped back to first
    s.closeLightbox()
    expect(s.lightboxIndex).toBeNull()
    expect(s.lightboxItem).toBeNull()
  })
})
