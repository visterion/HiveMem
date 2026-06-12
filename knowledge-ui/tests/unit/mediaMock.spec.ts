import { describe, it, expect, beforeEach } from 'vitest'
import { MockApiClient } from '../../src/api/mockClient'
import type { MediaItem } from '../../src/api/types'

describe('mock list_media', () => {
  let api: MockApiClient
  beforeEach(() => { api = new MockApiClient({ latencyMs: [0, 0] }) })

  it('returns image rows with the full MediaItem shape', async () => {
    const rows = await api.call<MediaItem[]>('list_media', { sort: 'newest', limit: 200 })
    expect(rows.length).toBeGreaterThan(5)
    const r = rows[0]
    expect(r).toHaveProperty('cell_id')
    expect(r).toHaveProperty('attachment_id')
    expect(r).toHaveProperty('width')
    expect(r).toHaveProperty('height')
    expect(r).toHaveProperty('taken_at')
    expect(r).toHaveProperty('place_name')
  })

  it('has at least one row with null EXIF (exercises the "—" fallback) and one geolocated', async () => {
    const rows = await api.call<MediaItem[]>('list_media', {})
    expect(rows.some(r => r.camera_make === null)).toBe(true)
    expect(rows.some(r => r.place_name !== null && r.gps_lat !== null)).toBe(true)
  })

  it('includes portrait and landscape aspect ratios', async () => {
    const rows = await api.call<MediaItem[]>('list_media', {})
    expect(rows.some(r => (r.width ?? 0) > (r.height ?? 0))).toBe(true)
    expect(rows.some(r => (r.height ?? 0) > (r.width ?? 0))).toBe(true)
  })
})
