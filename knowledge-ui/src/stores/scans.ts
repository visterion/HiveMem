import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import { useCellStore } from './cell'
import { useReaderStore } from './reader'
import { useUiStore } from './ui'
import type { Cell, DocumentRow, FacetCounts, SavedSearch, SearchDocumentRow } from '../api/types'

export type FacetKey = 'tag' | 'status' | 'realm' | 'year' | 'signal' | 'correspondent'
export interface SavedView { id: string; name: string; icon?: string; filter: Partial<Record<FacetKey, string[]>> }

const REALM = 'documents'
/** Standard server-side facet fields (no correspondent — that's derived client-side) */
const SERVER_FACET_FIELDS = ['tag', 'status', 'realm', 'year', 'signal'] as const
/** Page size for both browse (list_documents, paginated) and search (capped). */
const PAGE_SIZE = 100

function emptyFacets(): Record<FacetKey, Set<string>> {
  return { tag: new Set(), status: new Set(), realm: new Set(), year: new Set(), signal: new Set(), correspondent: new Set() }
}

export const useScansStore = defineStore('scans', {
  state: () => ({
    query: '',
    savedView: 'all' as string,
    facets: emptyFacets(),
    sort: 'newest' as 'newest' | 'oldest' | 'title',
    mode: 'grid' as 'grid' | 'list',
    selection: new Set<string>(),
    results: [] as (DocumentRow | SearchDocumentRow)[],
    facetCounts: {} as FacetCounts,
    openId: null as string | null,
    loading: false,
    offset: 0,
    /** True when the last list_documents page was full — more docs may exist (M54). */
    hasMore: false,
    // Monotonic token: only the latest load()/loadMore() commits results, so an
    // older, slower response can't overwrite a newer one (M53).
    loadSeq: 0,
    savedViews: [] as SavedView[],
    // Meta collected from every successful list_documents load (browse load()/
    // loadMore()), keyed by cell id. `search` never returns has_thumbnail/
    // attachment_id/page_count/status/correspondent (see SearchDocumentRow), so
    // search-mode results are enriched from this map instead. Limitation: only
    // docs seen via a list_documents page (capped at PAGE_SIZE=100, newest first)
    // are covered — a search hit outside the newest 100 browse rows keeps the
    // placeholder (no thumbnail meta available client-side).
    metaById: new Map<string, { has_thumbnail?: boolean; attachment_id?: string | null; page_count?: number | null; status?: string; correspondent?: string | null }>(),
  }),
  getters: {
    /**
     * Client-side refinement:
     * - year / realm / signal: server returns all; refine locally
     * - correspondent: filter against the `correspondent` field on DocumentRow (derived from fact:vendor/party)
     */
    filtered(s): (DocumentRow | SearchDocumentRow)[] {
      // search rows never carry `correspondent` (it's derived from fact:vendor/
      // fact:party via facet_count, which only covers browse mode) — applying a
      // stale correspondent facet selection while a query is active would filter
      // out every single search result (M17).
      const applyCorrespondent = !s.query.trim()
      return s.results.filter(d => {
        if (s.facets.year.size && !s.facets.year.has((d.created_at || '').slice(0, 4))) return false
        if (s.facets.realm.size && !s.facets.realm.has(d.realm)) return false
        if (s.facets.signal.size && !(d.signal && s.facets.signal.has(d.signal))) return false
        if (applyCorrespondent && s.facets.correspondent.size && !(d.correspondent && s.facets.correspondent.has(d.correspondent))) return false
        return true
      })
    },
    activeCount(s): number {
      const keys: FacetKey[] = ['tag', 'status', 'realm', 'year', 'signal', 'correspondent']
      return keys.reduce((n, k) => n + s.facets[k].size, 0)
    },
    /** Search results are capped at PAGE_SIZE (no server pagination) — a full page means truncation (M54). */
    searchTruncated(s): boolean {
      return !!s.query.trim() && s.results.length >= PAGE_SIZE
    },
    /** Total documents across all statuses, from the status facet buckets (which
     * already cover every status regardless of mode) — falls back to the loaded
     * result count when facets haven't loaded yet. Consumed by ScansPanel (sidebar
     * "N Dokumente") and ScansResults (grid "loaded von total" label). */
    totalDocs(s): number {
      const buckets = s.facetCounts.status
      if (buckets && buckets.length > 0) return buckets.reduce((n, b) => n + b.count, 0)
      return s.results.length
    },
  },
  actions: {
    // `browse` selects the count basis for list_documents (the Scans grid): with no
    // status facet selected, browse mode requests status='all' (all statuses, matching
    // facet_count's unfiltered default) instead of the tool's own default of
    // "committed"-only — otherwise the grid undercounts against the header/sidebar
    // totals, which already sum every status bucket. `search` keeps its own default
    // (status omitted) since search results never carry a `status` field anyway.
    serverArgs(browse = false): Record<string, unknown> {
      const tags = [...this.facets.tag]
      const status = this.facets.status.size ? [...this.facets.status][0] : (browse ? 'all' : undefined)
      const args: Record<string, unknown> = { realm: REALM }
      if (tags.length) args.tags = tags
      if (status) args.status = status
      return args
    },
    // Record has_thumbnail/attachment_id/page_count/status/correspondent for every
    // row of a list_documents page, so search-mode results (which never carry these
    // fields) can be enriched later via `metaById.get(id)`.
    updateMetaById(rows: DocumentRow[]) {
      for (const r of Array.isArray(rows) ? rows : []) {
        this.metaById.set(r.id, {
          has_thumbnail: r.has_thumbnail, attachment_id: r.attachment_id,
          page_count: r.page_count, status: r.status, correspondent: r.correspondent,
        })
      }
    },
    async load() {
      const seq = ++this.loadSeq
      this.loading = true
      try {
        const api = useApi()
        if (this.query.trim()) {
          // `include` REPLACES the tool's default field set (same as get_cell), so
          // 'summary' must be listed explicitly or every search-mode card/snippet
          // silently loses its text (M17). `status`, `attachment_id`,
          // `has_thumbnail`, `page_count` and `correspondent` are never returned by
          // search regardless of `include` — SearchDocumentRow types those as
          // optional so card/table rendering degrades gracefully instead of
          // assuming the full DocumentRow shape. Fetch list_documents in parallel
          // (status:'all', same basis as browse) to fill metaById with thumbnail
          // meta for search results to merge against — unless a prior browse/search
          // load already populated it, in which case reuse that (avoids a redundant
          // round-trip on every keystroke). Search now shares browse's status
          // basis: 'all' unless a status facet is selected, so the grid/search
          // count stays consistent with the sidebar facet totals (which already
          // sum every status bucket) — see totalDocs getter.
          const searchArgs = this.serverArgs(true)
          const searchPromise = api.call<SearchDocumentRow[]>('search', {
            query: this.query, realm: REALM, ...searchArgs,
            include: ['content', 'tags', 'created_at', 'summary'], limit: PAGE_SIZE,
          })
          const metaPromise = this.metaById.size === 0
            ? api.call<DocumentRow[]>('list_documents', { realm: REALM, status: 'all', sort: 'newest', limit: PAGE_SIZE })
            : Promise.resolve(null)
          const [searchRows, metaRows] = await Promise.all([searchPromise, metaPromise])
          if (seq !== this.loadSeq) return // stale — a newer load() owns the state (M53)
          if (metaRows) this.updateMetaById(metaRows)
          // Meta first, search fields last, so summary/score always win over any
          // stale browse-derived value (there shouldn't be an overlap, but this is
          // the documented precedence). NOTE: only covers docs within the newest
          // PAGE_SIZE (100) list_documents rows — a search hit outside that window
          // keeps the placeholder (no thumbnail meta available client-side).
          this.results = (searchRows ?? []).map(r => ({ ...this.metaById.get(r.id), ...r }))
          this.offset = this.results.length
          this.hasMore = false // search has no pagination; see searchTruncated getter
        } else {
          const rows = await api.call<DocumentRow[]>('list_documents', {
            ...this.serverArgs(true), sort: this.sort, limit: PAGE_SIZE, offset: 0,
          }) ?? []
          if (seq !== this.loadSeq) return
          this.updateMetaById(rows)
          this.results = rows
          this.offset = rows.length
          this.hasMore = rows.length >= PAGE_SIZE
        }
      } finally {
        if (seq === this.loadSeq) this.loading = false
      }
    },
    // Fetch the next list_documents page and append it (browse mode only) — before
    // this, `offset` never advanced and the collection was silently capped at 100 (M54).
    async loadMore() {
      if (this.loading || !this.hasMore || this.query.trim()) return
      const seq = ++this.loadSeq
      this.loading = true
      try {
        const rows = await useApi().call<DocumentRow[]>('list_documents', {
          ...this.serverArgs(true), sort: this.sort, limit: PAGE_SIZE, offset: this.offset,
        }) ?? []
        if (seq !== this.loadSeq) return
        this.updateMetaById(rows)
        this.results = [...this.results, ...rows]
        this.offset += rows.length
        this.hasMore = rows.length >= PAGE_SIZE
      } finally {
        if (seq === this.loadSeq) this.loading = false
      }
    },
    async loadFacets() {
      // Mirror load()'s M53 loadSeq guard: a slower loadFacets() triggered by an
      // earlier reload() must not clobber facetCounts computed for a newer one
      // (e.g. rapid facet toggling / typing).
      const seq = this.loadSeq
      const api = useApi()
      const args: Record<string, unknown> = {
        realm: REALM,
        fields: [...SERVER_FACET_FIELDS, 'fact:vendor', 'fact:party'],
      }
      if (this.query.trim()) args.query = this.query
      const tags = [...this.facets.tag]; if (tags.length) args.tags = tags
      const status = this.facets.status.size ? [...this.facets.status][0] : undefined; if (status) args.status = status
      const raw = await api.call<FacetCounts>('facet_count', args)
      if (seq !== this.loadSeq) return // stale — a newer load()/loadFacets() owns the state

      // Merge fact:vendor + fact:party into a synthetic 'correspondent' facet.
      // Counts are summed by value (deduplicated: same entity appearing in both
      // vendor and party for different docs is summed — expected to be rare in practice).
      const corrMap = new Map<string, number>()
      for (const entry of (raw['fact:vendor'] ?? [])) {
        corrMap.set(entry.value, (corrMap.get(entry.value) ?? 0) + entry.count)
      }
      for (const entry of (raw['fact:party'] ?? [])) {
        corrMap.set(entry.value, (corrMap.get(entry.value) ?? 0) + entry.count)
      }
      const correspondentFacet = [...corrMap.entries()]
        .sort((a, b) => b[1] - a[1])
        .map(([value, count]) => ({ value, count }))

      // Expose standard facets + correspondent; drop the raw fact: keys
      const merged: FacetCounts = {}
      for (const k of SERVER_FACET_FIELDS) {
        if (raw[k]) merged[k] = raw[k]
      }
      if (correspondentFacet.length) merged['correspondent'] = correspondentFacet
      this.facetCounts = merged
    },
    async reload() { await Promise.all([this.load(), this.loadFacets()]) },
    setQuery(q: string) { this.query = q },
    setSort(s: 'newest' | 'oldest' | 'title') { this.sort = s },
    setMode(m: 'grid' | 'list') { this.mode = m },
    toggleFacet(key: FacetKey, val: string) {
      const set = this.facets[key]
      if (key === 'status') {
        // The server accepts a single status and serverArgs() sends only one — make
        // status honest single-select (radio semantics) instead of silently applying
        // just the first of a multi-selection (L-F8).
        const had = set.has(val)
        set.clear()
        if (!had) set.add(val)
        return
      }
      set.has(val) ? set.delete(val) : set.add(val)
    },
    clearFacets() { this.facets = emptyFacets(); this.savedView = 'all' },
    setSavedView(id: string) {
      this.savedView = id
      this.facets = emptyFacets()
      if (id !== 'all') {
        const v = this.savedViews.find(x => x.id === id)
        if (v) for (const k of Object.keys(v.filter) as FacetKey[]) {
          const vals = v.filter[k]
          if (vals) this.facets[k] = new Set(vals)
        }
      }
    },

    // ── Saved views via server tools (replaces localStorage) ────────────────
    async loadSavedViews() {
      const api = useApi()
      const rows = await api.call<SavedSearch[]>('saved_searches', { action: 'list' })
      this.savedViews = rows.map(r => {
        let filter: Partial<Record<FacetKey, string[]>>
        if (typeof r.filter === 'string') {
          try { filter = JSON.parse(r.filter) } catch { filter = {} }
        } else {
          filter = (r.filter ?? {}) as Partial<Record<FacetKey, string[]>>
        }
        return { id: r.id, name: r.name, filter }
      })
    },
    async saveView(name: string, filter: Partial<Record<FacetKey, string[]>>) {
      const api = useApi()
      await api.call('saved_searches', { action: 'save', name, filter })
      await this.loadSavedViews()
    },
    async deleteView(id: string) {
      const api = useApi()
      await api.call('saved_searches', { action: 'delete', id })
      await this.loadSavedViews()
    },

    // ── Tag editing ──────────────────────────────────────────────────────────
    async editTags(cellId: string, add: string[], remove: string[]) {
      const api = useApi()
      if (add.length) await api.call('manage_tags', { cell_ids: [cellId], add })
      if (remove.length) await api.call('manage_tags', { cell_ids: [cellId], remove })
      await this.reload()
    },

    // ── Bulk actions ─────────────────────────────────────────────────────────
    async bulkTag(addTags?: string[], removeTags?: string[]) {
      const api = useApi()
      const cell_ids = [...this.selection]
      if (!cell_ids.length) return
      const add = addTags?.length ? addTags : undefined
      const remove = removeTags?.length ? removeTags : undefined
      await api.call('manage_tags', { cell_ids, add, remove })
      await this.reload()
    },
    async bulkReclassify(realm?: string, signal?: string, topic?: string) {
      const api = useApi()
      const cell_ids = [...this.selection]
      if (!cell_ids.length) return
      await api.call('reclassify', { cell_ids, realm, signal, topic })
      this.clearSelection()
      await this.reload()
    },

    // ── Selection ────────────────────────────────────────────────────────────
    toggleSelect(id: string) { this.selection.has(id) ? this.selection.delete(id) : this.selection.add(id) },
    clearSelection() { this.selection = new Set() },
    open(id: string) { this.openId = id },
    closeDetail() { this.openId = null },

    // Open a scan straight into the fullscreen document view. Fetches the full cell
    // (content + layers + attachments) and seeds the cellStore so the reader renders
    // the right document, then jumps to the scanned page (first attachment) — the
    // layered overview lives one tab away. Replaces the old click→detail-modal step.
    // focus 'document' lands on the scanned page (first attachment); 'info' lands on the
    // overview tab (summaries + raw text). Tapping the thumbnail wants the document, tapping
    // the title/text wants the info — the viewer stays reachable via its tab either way.
    async openDocument(id: string, focus: 'document' | 'info' = 'document') {
      this.openId = id
      const cells = useCellStore()
      const reader = useReaderStore()
      let initialTab = 'info'
      try {
        // include REPLACES get_cell's default field set, so the layers (summary/
        // key_points/insight) must be listed explicitly or the overview tab loses them.
        const full = await useApi().call<Cell>('get_cell', {
          cell_id: id,
          include: ['content', 'summary', 'key_points', 'insight', 'tags', 'attachments'],
        })
        await cells.open(full)
        const firstAtt = full.attachments?.[0]?.id
        if (focus === 'document' && firstAtt) initialTab = firstAtt
      } catch {
        // Rich fetch failed. Fall back to a plain load; if that fails too, do NOT
        // open the reader — it would render the previously selected cell (stale
        // cellStore.current) under this document (M55).
        try { await cells.load(id) } catch { /* handled below */ }
        if (cells.currentId !== id) {
          this.openId = null
          useUiStore().pushToast('error', 'Document could not be loaded')
          return
        }
      }
      reader.openReader(id, initialTab)
    },
  },
})
