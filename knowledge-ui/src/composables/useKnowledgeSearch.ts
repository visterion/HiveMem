import { reactive, ref, computed } from 'vue'
import { useApi } from '../api/useApi'
import type { SearchResult, FacetValue } from '../api/types'
import { cellLabel } from '../api/cellLabel'

export type KnowledgeFacetKey = 'realm' | 'signal' | 'tag'
export type KnowledgeSort = 'relevance' | 'newest' | 'oldest' | 'title'

export interface FacetState { realm: Set<string>; signal: Set<string>; tag: Set<string> }

/** Client-side multi-select filter over realm + signal (tags are filtered server-side). */
export function filterResults(rows: SearchResult[], f: FacetState): SearchResult[] {
  return rows.filter(c => {
    if (f.realm.size && !f.realm.has(c.realm)) return false
    if (f.signal.size && !(c.signal && f.signal.has(c.signal))) return false
    return true
  })
}

/** Pure, non-mutating sort. */
export function sortResults(rows: SearchResult[], sort: KnowledgeSort): SearchResult[] {
  const out = rows.slice()
  switch (sort) {
    case 'newest': out.sort((a, b) => b.created_at.localeCompare(a.created_at)); break
    case 'oldest': out.sort((a, b) => a.created_at.localeCompare(b.created_at)); break
    case 'title': out.sort((a, b) => cellLabel(a).localeCompare(cellLabel(b))); break
    case 'relevance':
    default: out.sort((a, b) => (b.score_total ?? 0) - (a.score_total ?? 0)); break
  }
  return out
}

export function useKnowledgeSearch() {
  const query = ref('')
  const facets = reactive<FacetState>({ realm: new Set(), signal: new Set(), tag: new Set() })
  const sort = ref<KnowledgeSort>('relevance')
  const results = ref<SearchResult[]>([])
  const facetCounts = ref<Record<string, FacetValue[]>>({})
  const loading = ref(false)

  function hasAnyFilter() {
    return !!query.value.trim() || facets.realm.size || facets.signal.size || facets.tag.size
  }

  async function run() {
    if (!hasAnyFilter()) { results.value = []; facetCounts.value = {}; return }
    loading.value = true
    try {
      const api = useApi()
      const searchArgs: Record<string, unknown> = {
        query: query.value, limit: 50, include: ['summary', 'created_at', 'scores'],
      }
      const tags = [...facets.tag]; if (tags.length) searchArgs.tags = tags
      if (facets.realm.size === 1) searchArgs.realm = [...facets.realm][0]

      const facetArgs: Record<string, unknown> = { fields: ['realm', 'signal', 'tag'] }
      if (query.value.trim()) facetArgs.query = query.value
      if (tags.length) facetArgs.tags = tags

      const [rows, counts] = await Promise.all([
        api.call<SearchResult[]>('search', searchArgs),
        api.call<Record<string, FacetValue[]>>('facet_count', facetArgs).catch(() => ({})),
      ])
      results.value = rows ?? []
      facetCounts.value = counts ?? {}
    } finally { loading.value = false }
  }

  function toggleFacet(key: KnowledgeFacetKey, value: string) {
    const set = facets[key]
    if (set.has(value)) set.delete(value); else set.add(value)
    run().catch(() => {})
  }
  function setSort(s: KnowledgeSort) { sort.value = s }
  function clearFacets() {
    facets.realm.clear(); facets.signal.clear(); facets.tag.clear()
    run().catch(() => {})
  }

  const shown = computed(() => sortResults(filterResults(results.value, facets), sort.value))

  return { query, facets, sort, results, facetCounts, loading, shown, run, toggleFacet, setSort, clearFacets }
}
