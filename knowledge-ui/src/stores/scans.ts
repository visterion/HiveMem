import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import type { DocumentRow, FacetCounts } from '../api/types'

export type FacetKey = 'tag' | 'status' | 'realm' | 'year' | 'signal'
export interface SavedView { id: string; name: string; icon?: string; filter: Partial<Record<FacetKey, string[]>> }

const REALM = 'documents'
const VIEWS_KEY = 'hivemem_scans_views'
const FACET_FIELDS: FacetKey[] = ['tag', 'status', 'realm', 'year', 'signal']

function emptyFacets(): Record<FacetKey, Set<string>> {
  return { tag: new Set(), status: new Set(), realm: new Set(), year: new Set(), signal: new Set() }
}

export const useScansStore = defineStore('scans', {
  state: () => ({
    query: '',
    savedView: 'all' as string,
    facets: emptyFacets(),
    sort: 'newest' as 'newest' | 'oldest' | 'title',
    mode: 'grid' as 'grid' | 'list',
    selection: new Set<string>(),
    results: [] as DocumentRow[],
    facetCounts: {} as FacetCounts,
    openId: null as string | null,
    loading: false,
    offset: 0,
    savedViews: [] as SavedView[],
  }),
  getters: {
    // year/realm/signal refine the loaded page client-side (server-side filtering for these is a later phase)
    filtered(s): DocumentRow[] {
      return s.results.filter(d => {
        if (s.facets.year.size && !s.facets.year.has((d.created_at || '').slice(0, 4))) return false
        if (s.facets.realm.size && !s.facets.realm.has(d.realm)) return false
        if (s.facets.signal.size && !(d.signal && s.facets.signal.has(d.signal))) return false
        return true
      })
    },
    activeCount(s): number {
      return FACET_FIELDS.reduce((n, k) => n + s.facets[k].size, 0)
    },
  },
  actions: {
    serverArgs(): Record<string, unknown> {
      const tags = [...this.facets.tag]
      const status = this.facets.status.size ? [...this.facets.status][0] : undefined
      const args: Record<string, unknown> = { realm: REALM }
      if (tags.length) args.tags = tags
      if (status) args.status = status
      return args
    },
    async load() {
      this.loading = true
      try {
        const api = useApi()
        if (this.query.trim()) {
          this.results = await api.call<DocumentRow[]>('search', {
            query: this.query, realm: REALM, ...this.serverArgs(),
            include: ['content', 'tags', 'created_at'], limit: 100,
          })
        } else {
          this.results = await api.call<DocumentRow[]>('list_documents', {
            ...this.serverArgs(), sort: this.sort, limit: 100, offset: this.offset,
          })
        }
      } finally { this.loading = false }
    },
    async loadFacets() {
      const api = useApi()
      const args: Record<string, unknown> = { realm: REALM, fields: FACET_FIELDS }
      if (this.query.trim()) args.query = this.query
      const tags = [...this.facets.tag]; if (tags.length) args.tags = tags
      const status = this.facets.status.size ? [...this.facets.status][0] : undefined; if (status) args.status = status
      this.facetCounts = await api.call<FacetCounts>('facet_count', args)
    },
    async reload() { await Promise.all([this.load(), this.loadFacets()]) },
    setQuery(q: string) { this.query = q },
    setSort(s: 'newest' | 'oldest' | 'title') { this.sort = s },
    setMode(m: 'grid' | 'list') { this.mode = m },
    toggleFacet(key: FacetKey, val: string) {
      const set = this.facets[key]
      set.has(val) ? set.delete(val) : set.add(val)
    },
    clearFacets() { this.facets = emptyFacets(); this.savedView = 'all' },
    setSavedView(id: string) {
      this.savedView = id
      this.facets = emptyFacets()
      if (id !== 'all') {
        const v = this.savedViews.find(x => x.id === id)
        if (v) for (const k of Object.keys(v.filter) as FacetKey[]) this.facets[k] = new Set(v.filter[k])
      }
    },
    loadSavedViews() {
      try { this.savedViews = JSON.parse(localStorage.getItem(VIEWS_KEY) || '[]') } catch { this.savedViews = [] }
    },
    saveView(name: string, filter: Partial<Record<FacetKey, string[]>>) {
      this.loadSavedViews()
      const id = 'v-' + name.toLowerCase().replace(/\s+/g, '-')
      this.savedViews = [...this.savedViews.filter(v => v.id !== id), { id, name, filter }]
      localStorage.setItem(VIEWS_KEY, JSON.stringify(this.savedViews))
    },
    deleteView(id: string) {
      this.loadSavedViews()
      this.savedViews = this.savedViews.filter(v => v.id !== id)
      localStorage.setItem(VIEWS_KEY, JSON.stringify(this.savedViews))
    },
    toggleSelect(id: string) { this.selection.has(id) ? this.selection.delete(id) : this.selection.add(id) },
    clearSelection() { this.selection = new Set() },
    open(id: string) { this.openId = id },
    closeDetail() { this.openId = null },
  },
})
