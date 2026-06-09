import { describe, expect, it, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCellStore } from '../../src/stores/cell'

describe('cell store selectedScores', () => {
  beforeEach(() => { setActivePinia(createPinia()); localStorage.setItem('hivemem_mock','true') })
  it('records scores when opening a SearchResult row and clears them', async () => {
    const s = useCellStore()
    const row: any = {
      id: 'x1', realm: 'r', signal: 's', topic: null, title: 't', content: 'c',
      summary: null, key_points: [], insight: null, tags: [], importance: 1,
      status: 'committed', created_by: 'u', created_at: '', valid_from: '', valid_until: null,
      score_total: 0.7, score_semantic: 0.8, score_keyword: 0.4, score_recency: 0.3,
      score_importance: 0.6, score_popularity: 0.2, score_graph_proximity: 0.5,
    }
    await s.open(row)
    expect(s.selectedScores?.score_total).toBe(0.7)
    expect(s.selectedScores?.score_semantic).toBe(0.8)
    s.clear()
    expect(s.selectedScores).toBeNull()
  })
  it('selectedScores is null when opening a plain cell without scores', async () => {
    const s = useCellStore()
    const cell: any = { id: 'y1', realm: 'r', signal: null, topic: null, title: 't', content: 'c', summary: null, key_points: [], insight: null, tags: [], importance: 1, status: 'committed', created_by: 'u', created_at: '', valid_from: '', valid_until: null }
    await s.open(cell)
    expect(s.selectedScores).toBeNull()
  })
})
