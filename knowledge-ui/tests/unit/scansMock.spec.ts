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

  it('list_documents rows include confidence (number or null)', async () => {
    const c = new MockApiClient()
    const rows = await c.call<any[]>('list_documents', { realm: 'documents', limit: 50 })
    expect(rows.length).toBeGreaterThan(0)
    for (const r of rows) {
      expect(r).toHaveProperty('confidence')
      expect(typeof r.confidence === 'number' || r.confidence === null).toBe(true)
    }
  })

  it('facet_count returns grouped counts for requested fields', async () => {
    const c = new MockApiClient()
    const fc = await c.call<Record<string, {value:string;count:number}[]>>('facet_count', { realm: 'documents', fields: ['tag','status'] })
    expect(fc).toHaveProperty('tag'); expect(fc).toHaveProperty('status')
    expect(Array.isArray(fc.tag)).toBe(true)
    if (fc.tag.length) { expect(fc.tag[0]).toHaveProperty('value'); expect(fc.tag[0]).toHaveProperty('count') }
  })

  it('facet_count handles fact:vendor and fact:party fields', async () => {
    const c = new MockApiClient()
    const fc = await c.call<Record<string, {value:string;count:number}[]>>('facet_count', {
      realm: 'documents', fields: ['fact:vendor', 'fact:party'],
    })
    expect(fc).toHaveProperty('fact:vendor')
    expect(fc).toHaveProperty('fact:party')
    expect(Array.isArray(fc['fact:vendor'])).toBe(true)
    expect(Array.isArray(fc['fact:party'])).toBe(true)
    // At least some vendor values should be present
    expect(fc['fact:vendor'].length).toBeGreaterThan(0)
  })

  it('search honors a status filter arg without throwing', async () => {
    const c = new MockApiClient()
    const rows = await c.call<any[]>('search', { query: 'a', status: 'committed', tags: ['contract'] })
    expect(Array.isArray(rows)).toBe(true)
  })

  it('saved_searches save + list + delete round-trip', async () => {
    const c = new MockApiClient()

    // list starts empty
    const empty = await c.call<any[]>('saved_searches', { action: 'list' })
    expect(empty).toEqual([])

    // save a search — mock returns filter as JSON string (mirrors real backend filter::text)
    const saved = await c.call<any>('saved_searches', { action: 'save', name: 'Invoices 2025', filter: { tag: ['invoice'] } })
    expect(saved).toHaveProperty('id')
    expect(saved.name).toBe('Invoices 2025')
    expect(saved.filter).toBe(JSON.stringify({ tag: ['invoice'] }))

    // list shows it
    const list = await c.call<any[]>('saved_searches', { action: 'list' })
    expect(list.length).toBe(1)
    expect(list[0].name).toBe('Invoices 2025')

    // upsert by same name
    await c.call('saved_searches', { action: 'save', name: 'Invoices 2025', filter: { tag: ['invoice', 'paid'] } })
    const list2 = await c.call<any[]>('saved_searches', { action: 'list' })
    expect(list2.length).toBe(1)
    expect(list2[0].filter).toBe(JSON.stringify({ tag: ['invoice', 'paid'] }))

    // delete
    const del = await c.call<any>('saved_searches', { action: 'delete', id: list2[0].id })
    expect(del.deleted).toBe(true)
    const list3 = await c.call<any[]>('saved_searches', { action: 'list' })
    expect(list3).toEqual([])
  })

  it('manage_tags add / remove mutate mock cell', async () => {
    const c = new MockApiClient()
    const res1 = await c.call<any>('manage_tags', { cell_ids: ['doc-contract-001'], add: ['important'] })
    expect(res1.updated).toBe(1)

    // Confirm tag is now present via list_documents
    const rows = await c.call<any[]>('list_documents', { realm: 'documents' })
    const doc = rows.find((r: any) => r.id === 'doc-contract-001')
    expect(doc?.tags).toContain('important')

    // Remove it
    const res2 = await c.call<any>('manage_tags', { cell_ids: ['doc-contract-001'], remove: ['important'] })
    expect(res2.updated).toBe(1)
  })

  it('manage_tags mutates multiple cells', async () => {
    const c = new MockApiClient()
    const res = await c.call<any>('manage_tags', {
      cell_ids: ['doc-invoice-001', 'doc-invoice-002'],
      add: ['reviewed'],
      remove: ['paid'],
    })
    expect(res.updated).toBe(2)
  })

  it('reclassify changes realm on multiple cells', async () => {
    const c = new MockApiClient()
    const res = await c.call<any>('reclassify', {
      cell_ids: ['doc-photo-001'],
      signal: 'archive',
    })
    expect(res.updated).toBe(1)
  })
})
