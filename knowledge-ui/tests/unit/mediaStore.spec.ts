import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useMediaStore } from '../../src/stores/media'
import { resetApi } from '../../src/api/useApi'
import { MockApiClient } from '../../src/api/mockClient'
import type { MediaItem } from '../../src/api/types'

function photo(attachmentId: string): MediaItem {
  return {
    cell_id: 'c-' + attachmentId, attachment_id: attachmentId, realm: 'private', summary: null,
    tags: [], mime_type: 'image/jpeg', size_bytes: 1000, created_at: '2026-01-01T00:00:00Z',
    taken_at: '2026-01-01T00:00:00Z', width: 100, height: 100, camera_make: null, camera_model: null,
    gps_lat: null, gps_lon: null, place_name: null, thumbnail_uri: null, content_uri: null,
  }
}

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

  it('loadMore() de-duplicates by attachment_id when a new photo shifts the offset window (E6)', async () => {
    // Page 1 returns p1, p2, p3 (limit 3, mocked via a small PAGE_SIZE is not
    // configurable here, so simulate the shift directly): a photo lands between
    // page 1 and page 2, so page 2 (fetched at offset=photos.length) re-includes
    // the last photo of page 1 (p3) shifted down by the insert.
    let call = 0
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string, args?: Record<string, unknown>) => {
      if (tool !== 'list_media') return {}
      call++
      if (call === 1) return [photo('p1'), photo('p2'), photo('p3')]
      // offset === 3 (photos.length after page 1); overlaps with p3 from page 1.
      expect(args?.offset).toBe(3)
      return [photo('p3'), photo('p4')]
    })
    const s = useMediaStore()
    await s.load()
    expect(s.photos.map(p => p.attachment_id)).toEqual(['p1', 'p2', 'p3'])
    s.hasMore = true // page 1 alone doesn't fill PAGE_SIZE; force loadMore() to proceed
    await s.loadMore()
    expect(s.photos.map(p => p.attachment_id)).toEqual(['p1', 'p2', 'p3', 'p4']) // p3 not duplicated
  })

  it('startClock() refreshes nowTick periodically so date-bucket boundaries actually roll over (E6)', () => {
    vi.useFakeTimers()
    try {
      const s = useMediaStore()
      const initial = s.nowTick
      s.startClock(60_000)
      vi.advanceTimersByTime(60_000)
      expect(s.nowTick).toBeGreaterThan(initial)
      s.stopClock()
      const afterStop = s.nowTick
      vi.advanceTimersByTime(120_000)
      expect(s.nowTick).toBe(afterStop) // stopped — no further ticks
    } finally {
      vi.useRealTimers()
    }
  })
})
