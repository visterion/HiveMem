import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import { useCellStore } from './cell'
import { useReaderStore } from './reader'
import type { Cell, DocumentRow, FacetCounts, SavedSearch } from '../api/types'

export type FacetKey = 'tag' | 'status' | 'realm' | 'year' | 'signal' | 'correspondent'
export interface SavedView { id: string; name: string; icon?: string; filter: Partial<Record<FacetKey, string[]>> }

const REALM = 'documents'
/** Standard server-side facet fields (no correspondent — that's derived client-side) */
const SERVER_FACET_FIELDS = ['tag', 'status', 'realm', 'year', 'signal'] as const

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
    results: [] as DocumentRow[],
    facetCounts: {} as FacetCounts,
    openId: null as string | null,
    loading: false,
    offset: 0,
    savedViews: [] as SavedView[],
  }),
  getters: {
    /**
     * Client-side refinement:
     * - year / realm / signal: server returns all; refine locally
     * - correspondent: filter against the `correspondent` field on DocumentRow (derived from fact:vendor/party)
     */
    filtered(s): DocumentRow[] {
      return s.results.filter(d => {
        if (s.facets.year.size && !s.facets.year.has((d.created_at || '').slice(0, 4))) return false
        if (s.facets.realm.size && !s.facets.realm.has(d.realm)) return false
        if (s.facets.signal.size && !(d.signal && s.facets.signal.has(d.signal))) return false
        if (s.facets.correspondent.size && !(d.correspondent && s.facets.correspondent.has(d.correspondent))) return false
        return true
      })
    },
    activeCount(s): number {
      const keys: FacetKey[] = ['tag', 'status', 'realm', 'year', 'signal', 'correspondent']
      return keys.reduce((n, k) => n + s.facets[k].size, 0)
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
      const args: Record<string, unknown> = {
        realm: REALM,
        fields: [...SERVER_FACET_FIELDS, 'fact:vendor', 'fact:party'],
      }
      if (this.query.trim()) args.query = this.query
      const tags = [...this.facets.tag]; if (tags.length) args.tags = tags
      const status = this.facets.status.size ? [...this.facets.status][0] : undefined; if (status) args.status = status
      const raw = await api.call<FacetCounts>('facet_count', args)

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
      const rows = await api.call<SavedSearch[]>('list_saved_searches')
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
      await api.call('save_search', { name, filter })
      await this.loadSavedViews()
    },
    async deleteView(id: string) {
      const api = useApi()
      await api.call('delete_saved_search', { id })
      await this.loadSavedViews()
    },

    // ── Tag editing ──────────────────────────────────────────────────────────
    async editTags(cellId: string, add: string[], remove: string[]) {
      const api = useApi()
      if (add.length) await api.call('add_tags', { cell_id: cellId, tags: add })
      if (remove.length) await api.call('remove_tags', { cell_id: cellId, tags: remove })
      await this.reload()
    },

    // ── Bulk actions ─────────────────────────────────────────────────────────
    async bulkTag(addTags?: string[], removeTags?: string[]) {
      const api = useApi()
      const cell_ids = [...this.selection]
      if (!cell_ids.length) return
      const add_tags = addTags?.length ? addTags : undefined
      const remove_tags = removeTags?.length ? removeTags : undefined
      await api.call('bulk_tag', { cell_ids, add_tags, remove_tags })
      await this.reload()
    },
    async bulkReclassify(realm?: string, signal?: string, topic?: string) {
      const api = useApi()
      const cell_ids = [...this.selection]
      if (!cell_ids.length) return
      await api.call('bulk_reclassify', { cell_ids, realm, signal, topic })
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
      } catch { /* open the reader anyway; it falls back via ensureAttachments */ }
      reader.openReader(id, initialTab)
    },
  },
})
