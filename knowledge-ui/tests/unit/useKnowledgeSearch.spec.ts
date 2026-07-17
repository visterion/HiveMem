import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { filterResults, normalizeFacetCounts, sortResults, useKnowledgeSearch } from '../../src/composables/useKnowledgeSearch'
import { resetApi } from '../../src/api/useApi'
import { MockApiClient } from '../../src/api/mockClient'
import { NO_REALM } from '../../src/composables/realmMeta'
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
  it('a toggled-on null-realm facet bucket keeps null-realm cells (regression, Finding 1)', () => {
    // Reproduces the prod regression: the backend's realm facet has no `IS NOT NULL`
    // filter, so facet_count legitimately emits a null-valued bucket for unclassified
    // inbox cells. Once normalizeFacetCounts() maps that bucket's value to NO_REALM and
    // it gets toggled on, a null-realm result row must survive the filter rather than
    // every row being dropped (the old `!c.realm || !f.realm.has(c.realm)` matched
    // `!c.realm` for every unclassified cell once any realm facet was selected).
    const unclassified = { ...r({ id: 'd', signal: 'facts' }), realm: null }
    const withUnclassified = [...rows, unclassified]
    expect(
      filterResults(withUnclassified, { realm: new Set([NO_REALM]), signal: new Set(), tag: new Set() })
        .map(x => x.id),
    ).toEqual(['d'])
  })
})

describe('normalizeFacetCounts', () => {
  it('maps a null-valued facet bucket to NO_REALM (Finding 1 ingestion boundary)', () => {
    const raw = { realm: [{ value: 'work', count: 5 }, { value: null, count: 1 }], tag: [{ value: 'a', count: 2 }] }
    expect(normalizeFacetCounts(raw)).toEqual({
      realm: [{ value: 'work', count: 5 }, { value: NO_REALM, count: 1 }],
      tag: [{ value: 'a', count: 2 }],
    })
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

describe('run()', () => {
  beforeEach(() => {
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
  })
  afterEach(() => vi.restoreAllMocks())

  it('requests the full field set so opened results are never cached partial (C3)', async () => {
    let include: string[] | undefined
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string, args?: Record<string, unknown>) => {
      if (tool === 'search') { include = args?.include as string[]; return [] }
      return {}
    })
    const s = useKnowledgeSearch()
    s.query.value = 'x'
    await s.run()
    expect(include).toEqual(expect.arrayContaining(
      ['content', 'tags', 'key_points', 'insight', 'importance', 'summary', 'created_at', 'scores'],
    ))
  })

  it('ignores stale responses from an older run (M53)', async () => {
    let resolveFirst!: (rows: SearchResult[]) => void
    let searchCalls = 0
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation((tool: string) => {
      if (tool === 'facet_count') return Promise.resolve({})
      searchCalls++
      if (searchCalls === 1) return new Promise(res => { resolveFirst = res })
      return Promise.resolve([r({ id: 'new' })])
    })
    const s = useKnowledgeSearch()
    s.query.value = 'first'
    const p1 = s.run()
    s.query.value = 'second'
    const p2 = s.run()
    await p2
    expect(s.results.value.map(x => x.id)).toEqual(['new'])
    expect(s.loading.value).toBe(false)
    resolveFirst([r({ id: 'old' })]) // stale response arrives late
    await p1
    expect(s.results.value.map(x => x.id)).toEqual(['new'])
    expect(s.loading.value).toBe(false)
  })

  it('clearing all filters invalidates in-flight responses (M53)', async () => {
    let resolveFirst!: (rows: SearchResult[]) => void
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation((tool: string) => {
      if (tool === 'facet_count') return Promise.resolve({})
      return new Promise(res => { resolveFirst = res })
    })
    const s = useKnowledgeSearch()
    s.query.value = 'x'
    const p1 = s.run()
    s.query.value = ''
    await s.run() // clear branch
    resolveFirst([r({ id: 'late' })])
    await p1
    expect(s.results.value).toEqual([])
    expect(s.loading.value).toBe(false)
  })
})
