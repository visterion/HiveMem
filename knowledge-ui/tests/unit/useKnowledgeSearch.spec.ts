import { describe, it, expect } from 'vitest'
import { filterResults, sortResults } from '../../src/composables/useKnowledgeSearch'
import type { SearchResult } from '../../src/api/types'

function r(p: Partial<SearchResult> & { summary?: string | null }): SearchResult {
  return {
    id: p.id ?? '1', realm: p.realm ?? 'work', signal: p.signal ?? 'facts',
    topic: p.topic ?? null, title: p.title ?? '', content: '', summary: p.summary ?? null,
    key_points: [], insight: null, tags: p.tags ?? [], importance: 1, status: 'committed',
    created_by: 'x', created_at: p.created_at ?? '2026-01-01T00:00:00Z', valid_from: '2026-01-01T00:00:00Z',
    valid_until: null, score_total: p.score_total ?? 0, score_semantic: 0, score_keyword: 0,
    score_recency: 0, score_importance: 0, score_popularity: 0, score_graph_proximity: 0,
  } as SearchResult
}

describe('filterResults', () => {
  const rows = [
    r({ id: 'a', realm: 'work', signal: 'facts' }),
    r({ id: 'b', realm: 'personal', signal: 'events' }),
    r({ id: 'c', realm: 'work', signal: 'events' }),
  ]
  it('no facets → all', () => {
    expect(filterResults(rows, { realm: new Set(), signal: new Set(), tag: new Set() }).map(x => x.id))
      .toEqual(['a', 'b', 'c'])
  })
  it('realm facet (multi) keeps matching realms', () => {
    expect(filterResults(rows, { realm: new Set(['work']), signal: new Set(), tag: new Set() }).map(x => x.id))
      .toEqual(['a', 'c'])
  })
  it('signal facet narrows further (AND across fields)', () => {
    expect(filterResults(rows, { realm: new Set(['work']), signal: new Set(['events']), tag: new Set() }).map(x => x.id))
      .toEqual(['c'])
  })
})

describe('sortResults', () => {
  // cellLabel uses summary → content → topic → '#'+id; use summary to drive label
  const rows = [
    r({ id: 'old-hi', created_at: '2025-01-01T00:00:00Z', score_total: 0.9, summary: 'Beta' }),
    r({ id: 'new-lo', created_at: '2026-06-01T00:00:00Z', score_total: 0.1, summary: 'Alpha' }),
  ]
  it('relevance → score_total desc', () => {
    expect(sortResults(rows, 'relevance').map(x => x.id)).toEqual(['old-hi', 'new-lo'])
  })
  it('newest → created_at desc', () => {
    expect(sortResults(rows, 'newest').map(x => x.id)).toEqual(['new-lo', 'old-hi'])
  })
  it('oldest → created_at asc', () => {
    expect(sortResults(rows, 'oldest').map(x => x.id)).toEqual(['old-hi', 'new-lo'])
  })
  it('title → label A–Z', () => {
    expect(sortResults(rows, 'title').map(x => x.id)).toEqual(['new-lo', 'old-hi'])
  })
  it('does not mutate input', () => {
    const copy = [...rows]
    sortResults(rows, 'newest')
    expect(rows).toEqual(copy)
  })
})
