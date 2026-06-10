import { describe, it, expect, beforeEach } from 'vitest'
import { MockApiClient } from '../../src/api/mockClient'
import type { FacetValue, SearchResult } from '../../src/api/types'

describe('mock facet_count + search for realms', () => {
  let api: MockApiClient
  beforeEach(() => { api = new MockApiClient({ latencyMs: [0, 0] }) })

  it('facet_count(fields:[realm]) with no realm returns multiple realms', async () => {
    const res = await api.call<Record<string, FacetValue[]>>('facet_count', { fields: ['realm'] })
    expect(Array.isArray(res.realm)).toBe(true)
    expect(res.realm.length).toBeGreaterThan(1)
    const total = res.realm.reduce((n, r) => n + r.count, 0)
    expect(total).toBeGreaterThan(8) // more than just the 8 'documents' cells
  })

  it('search honors the realm arg', async () => {
    const all = await api.call<SearchResult[]>('search', { realm: 'documents' })
    expect(all.length).toBeGreaterThan(0)
    expect(all.every(c => c.realm === 'documents')).toBe(true)
  })
})
