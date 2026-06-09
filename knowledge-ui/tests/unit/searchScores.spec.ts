import { describe, expect, it } from 'vitest'
import { MockApiClient } from '../../src/api/mockClient'

describe('mock search scores', () => {
  it('returns rows carrying per-signal scores', async () => {
    const c = new MockApiClient()
    const rows = await c.call<any[]>('search', { query: '', limit: 5 })
    expect(rows.length).toBeGreaterThan(0)
    for (const r of rows) {
      for (const k of ['score_total','score_semantic','score_keyword','score_recency','score_importance','score_popularity','score_graph_proximity']) {
        expect(typeof r[k], k).toBe('number')
        expect(r[k]).toBeGreaterThanOrEqual(0)
        expect(r[k]).toBeLessThanOrEqual(1)
      }
    }
  })
})
