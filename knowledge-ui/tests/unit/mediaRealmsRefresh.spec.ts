import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApi } from '../../src/api/useApi'
import { MockApiClient } from '../../src/api/mockClient'
import { useMediaStore } from '../../src/stores/media'
import { useRealmsStore } from '../../src/stores/realms'
import type { MediaItem } from '../../src/api/types'

function photo(id: string): MediaItem {
  return {
    cell_id: id, attachment_id: `att-${id}`, realm: 'media', summary: null, tags: [],
    mime_type: 'image/jpeg', size_bytes: 1, created_at: '2026-01-01T00:00:00Z',
    taken_at: '2026-01-01T00:00:00Z', width: 1, height: 1,
    camera_make: null, camera_model: null, gps_lat: null, gps_lon: null,
    place_name: null, thumbnail_uri: null, content_uri: null,
  }
}

describe('media store — pagination + refresh (M57)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
  })
  afterEach(() => vi.restoreAllMocks())

  function stubPages() {
    const calls: Array<Record<string, unknown>> = []
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string, args?: Record<string, unknown>) => {
      if (tool !== 'list_media') return {}
      calls.push(args ?? {})
      const offset = (args?.offset as number) ?? 0
      if (offset === 0) return Array.from({ length: 200 }, (_, i) => photo(`p${i}`))
      return Array.from({ length: 30 }, (_, i) => photo(`p${offset + i}`))
    })
    return calls
  }

  it('loadMore() appends the next page after a full first page', async () => {
    const calls = stubPages()
    const s = useMediaStore()
    await s.load()
    expect(s.photos.length).toBe(200)
    expect(s.hasMore).toBe(true)

    await s.loadMore()
    expect(calls[1].offset).toBe(200)
    expect(s.photos.length).toBe(230)
    expect(s.hasMore).toBe(false)

    await s.loadMore() // exhausted → no request
    expect(calls.length).toBe(2)
  })

  it('refresh() drops the loaded guard and refetches (uploads become visible)', async () => {
    const calls = stubPages()
    const s = useMediaStore()
    await s.load()
    await s.load() // guarded — no second request
    expect(calls.length).toBe(1)

    await s.refresh()
    expect(calls.length).toBe(2)
    expect(s.loaded).toBe(true)
    expect(s.photos.length).toBe(200)
  })
})

describe('realms store — invalidation (M57)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
  })
  afterEach(() => vi.restoreAllMocks())

  it('invalidate() makes the next loadRealms() refetch', async () => {
    const spy = vi.spyOn(MockApiClient.prototype, 'call').mockResolvedValue(
      { realm: [{ value: 'work', count: 3 }] } as never
    )
    const s = useRealmsStore()
    await s.loadRealms()
    await s.loadRealms() // cached — no second request
    expect(spy).toHaveBeenCalledTimes(1)

    s.invalidate()
    await s.loadRealms()
    expect(spy).toHaveBeenCalledTimes(2)
    expect(s.realms.map(r => r.id)).toEqual(['work'])
  })
})
