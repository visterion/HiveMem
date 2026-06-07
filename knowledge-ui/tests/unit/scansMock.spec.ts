import { describe, expect, it } from 'vitest'
import { MockApiClient } from '../../src/api/mockClient'

describe('mock scans endpoints', () => {
  it('list_documents returns document rows with attachment fields, honoring filters + sort', async () => {
    const c = new MockApiClient()
    const rows = await c.call<any[]>('list_documents', { realm: 'documents', sort: 'newest', limit: 50 })
    expect(Array.isArray(rows)).toBe(true)
    expect(rows.length).toBeGreaterThan(0)
    const r = rows[0]
    for (const k of ['id','realm','tags','status','created_at']) expect(r, k).toHaveProperty(k)
    // attachment-join fields present (may be null for some)
    expect(r).toHaveProperty('page_count')
    expect(r).toHaveProperty('has_thumbnail')
    // newest-first
    if (rows.length > 1) expect(rows[0].created_at >= rows[1].created_at).toBe(true)
  })
  it('facet_count returns grouped counts for requested fields', async () => {
    const c = new MockApiClient()
    const fc = await c.call<Record<string, {value:string;count:number}[]>>('facet_count', { realm: 'documents', fields: ['tag','status'] })
    expect(fc).toHaveProperty('tag'); expect(fc).toHaveProperty('status')
    expect(Array.isArray(fc.tag)).toBe(true)
    if (fc.tag.length) { expect(fc.tag[0]).toHaveProperty('value'); expect(fc.tag[0]).toHaveProperty('count') }
  })
  it('search honors a status filter arg without throwing', async () => {
    const c = new MockApiClient()
    const rows = await c.call<any[]>('search', { query: 'a', status: 'committed', tags: ['contract'] })
    expect(Array.isArray(rows)).toBe(true)
  })
})
