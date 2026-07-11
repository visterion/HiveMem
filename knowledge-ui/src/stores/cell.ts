import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import { useRealmsStore } from './realms'
import type { Cell, Fact, Tunnel, SearchResult } from '../api/types'
import type { ParsedNote } from '../composables/obsidianImport'

type CellEntry = { cell: Cell; facts: Fact[]; tunnels: Tunnel[] }

// Fill fields the cached row is missing (undefined) from a richer row, without
// overwriting locally-known values (e.g. optimistic tag edits). Search rows are
// partial — the server treats an explicit `include` as a replacement of the default
// field set — so cached entries must be healable, or the reader shows blanks and
// an edit seeds the editor with undefined and destroys the real content (C3).
function fillMissingFields(target: Cell, source: Cell): void {
  const t = target as unknown as Record<string, unknown>
  for (const [k, v] of Object.entries(source)) {
    if (t[k] === undefined) t[k] = v
  }
}

// Cells have no `title`; facts are keyed on a human subject. Use summary/topic when
// available, and skip the lookup for bare cells (e.g. scans) so a blank subject never
// triggers a "Missing subject" error. Module-level helper to keep the store's `this`
// typing simple (no cross-action calls).
async function fetchFacts(cell: Cell): Promise<Fact[]> {
  const subject = cell.summary?.trim() || cell.topic?.trim()
  if (!subject) return []
  return useApi()
    .call<{ cells: unknown[]; facts: Fact[]; tunnels: unknown[] }>('entity_overview', { subject, depth: 'quick' })
    .then(r => r.facts ?? [])
    .catch(() => [])
}

export const useCellStore = defineStore('cell', {
  state: () => ({
    cache: new Map<string, CellEntry>(),
    currentId: null as string | null,
    loading: false,
    selectedScores: null as SearchResult | null,
    // Monotonic token: only the latest load()/open() commits currentId/loading, so
    // out-of-order responses can't clobber the user's newest selection (M52).
    loadSeq: 0,
    // Consumed exactly once by router.ts's afterEach: set by CellInspector's "Im Graph
    // zeigen" right before it pushes search -> graph, so that one route-name change
    // does NOT clear the selection (the whole point of the button is to keep it).
    preserveOnce: false
  }),
  getters: {
    current(s): CellEntry | null {
      return s.currentId ? s.cache.get(s.currentId) ?? null : null
    }
  },
  actions: {
    async load(id: string) {
      const seq = ++this.loadSeq
      // A plain load is not a search hit — clear a stale score breakdown so the
      // inspector doesn't show the previous result's scores for this cell (L-F9).
      this.selectedScores = null
      this.loading = true
      try {
        if (!this.cache.has(id)) {
          const api = useApi()
          const [cell, tunnels] = await Promise.all([
            api.call<Cell>('get_cell', { cell_id: id }),
            api.call<{ edges: Tunnel[] }>('traverse', { cell_id: id, max_depth: 1 })
              .then(r => r.edges ?? [])
              .catch(() => [])
          ])
          const facts = await fetchFacts(cell)
          this.store(id, { cell, facts, tunnels })
        } else {
          this.touch(id)
        }
        if (seq === this.loadSeq) this.currentId = id
      } finally {
        if (seq === this.loadSeq) this.loading = false
      }
    },
    // Open a cell using an already-fetched (rich) row — e.g. a search result that
    // carries content/summary. Avoids a second get_cell that would drop those fields,
    // so the panel shows a real label and the OCR/parsed content immediately.
    async open(cell: Cell) {
      const seq = ++this.loadSeq
      this.selectedScores = (cell && 'score_total' in cell) ? (cell as unknown as SearchResult) : null
      this.loading = true
      try {
        const id = cell.id
        const cached = this.cache.get(id)
        if (!cached) {
          const tunnels = await useApi().call<{ edges: Tunnel[] }>('traverse', { cell_id: id, max_depth: 1 })
            .then(r => r.edges ?? [])
            .catch(() => [])
          const facts = await fetchFacts(cell)
          this.store(id, { cell, facts, tunnels })
        } else {
          // Upgrade an earlier (possibly partial) cached row with any richer fields
          // this row carries — cache.has must never freeze a partial row (C3).
          fillMissingFields(cached.cell, cell)
          this.touch(id)
        }
        if (seq === this.loadSeq) this.currentId = id
      } finally {
        if (seq === this.loadSeq) this.loading = false
      }
    },
    // Search rows are partial: no attachments, and possibly no content/tags (their
    // `include` replaces the server's default field set). When the reader needs the
    // full cell, fetch it via get_cell and merge every missing field into the cached
    // entry — not just attachments, or a partial row renders a blank reader and an
    // edit overwrites the real content (C3). Idempotent.
    async ensureAttachments(id: string) {
      const entry = this.cache.get(id)
      if (!entry) return
      const c = entry.cell as Partial<Cell>
      if (c.attachments !== undefined && c.content !== undefined && c.tags !== undefined) return
      const full = await useApi().call<Cell>('get_cell', { cell_id: id }).catch(() => null)
      const current = this.cache.get(id)
      if (full && current) {
        fillMissingFields(current.cell, full)
        // get_cell returned no attachments field → record "none" so we don't refetch forever.
        if (current.cell.attachments === undefined) current.cell.attachments = full.attachments ?? []
      }
    },
    // Bulk-import parsed Obsidian notes: one add_cell per note (default realm when the
    // note has none), stub cells for [[wiki-links]] whose target isn't in the vault, then
    // an add_tunnel per link. Title→id mapping resolves links to the cells just created.
    // Partial failures don't abort the import: failed notes/links are counted in
    // `failed` and the rest of the vault still lands (L-F12).
    async importObsidian(
      notes: ParsedNote[],
      opts: { defaultRealm: string; onProgress?: (done: number, total: number) => void }
    ): Promise<{ cellsCreated: number; tunnelsCreated: number; stubsCreated: number; failed: number }> {
      const api = useApi()
      const titleToId = new Map<string, string>()
      const total = notes.length
      let done = 0
      let cellsCreated = 0
      let failed = 0
      for (const note of notes) {
        try {
          const res = await api.call<{ id: string }>('add_cell', {
            content: note.content,
            realm: note.realm || opts.defaultRealm,
            ...(note.signal ? { signal: note.signal } : {}),
            topic: note.title,
            ...(note.tags.length ? { tags: note.tags } : {}),
            ...(note.validFrom ? { valid_from: note.validFrom } : {})
          })
          titleToId.set(note.title, res.id)
          cellsCreated++
        } catch {
          failed++ // keep importing the remaining notes; links to this one are skipped/stubbed
        }
        opts.onProgress?.(++done, total)
      }
      let stubsCreated = 0
      let tunnelsCreated = 0
      for (const note of notes) {
        const fromId = titleToId.get(note.title)
        if (!fromId) continue
        for (const link of note.links) {
          try {
            let targetId = titleToId.get(link)
            if (!targetId) {
              const stub = await api.call<{ id: string }>('add_cell', { content: link, topic: link, realm: opts.defaultRealm })
              targetId = stub.id
              titleToId.set(link, targetId)
              stubsCreated++
            }
            await api.call('add_tunnel', { from_cell: fromId, to_cell: targetId, relation: 'related_to' })
            tunnelsCreated++
          } catch {
            failed++
          }
        }
      }
      if (cellsCreated || stubsCreated) useRealmsStore().invalidate()
      return { cellsCreated, tunnelsCreated, stubsCreated, failed }
    },
    // Create a tunnel from the current cell to another via add_tunnel, appending the
    // result to the source cell's cached tunnels so the reader shows it immediately.
    async addTunnel(fromId: string, toId: string, relation: string, note?: string):
      Promise<{ id: string; from_cell: string; to_cell: string; relation: string; note: string | null; status: string }> {
      const res = await useApi().call<{ id: string; from_cell: string; to_cell: string; relation: string; note: string | null; status: string }>(
        'add_tunnel',
        { from_cell: fromId, to_cell: toId, relation, ...(note ? { note } : {}) }
      )
      const entry = this.cache.get(fromId)
      if (entry) {
        entry.tunnels = [...entry.tunnels, {
          id: res.id,
          from_cell: res.from_cell,
          to_cell: res.to_cell,
          relation: res.relation as Tunnel['relation'],
          note: res.note ?? null,
          status: (res.status ?? 'committed') as Tunnel['status'],
          created_at: new Date().toISOString(),
          valid_until: null,
        }]
      }
      return res
    },
    // Inline tag editing via manage_tags. The cached cell's tags are updated
    // optimistically (union / minus) so the reader reflects the change without a re-fetch.
    async addTags(id: string, tags: string[]): Promise<void> {
      if (!tags.length) return
      await useApi().call('manage_tags', { cell_ids: [id], add: tags })
      const entry = this.cache.get(id)
      if (entry) {
        const merged = new Set([...(entry.cell.tags ?? []), ...tags])
        entry.cell.tags = [...merged]
      }
    },
    async removeTags(id: string, tags: string[]): Promise<void> {
      if (!tags.length) return
      await useApi().call('manage_tags', { cell_ids: [id], remove: tags })
      const entry = this.cache.get(id)
      if (entry) {
        const drop = new Set(tags)
        entry.cell.tags = (entry.cell.tags ?? []).filter(t => !drop.has(t))
      }
    },
    // Create a new cell via add_cell, then load + select it so the reader shows it.
    // add_cell returns { inserted, id, ... }; the full row comes from get_cell.
    async addCell(opts: {
      content: string; realm?: string; signal?: string | null; topic?: string | null
      summary?: string; importance?: number
    }): Promise<{ id: string }> {
      const api = useApi()
      const args: Record<string, unknown> = { content: opts.content }
      if (opts.realm) args.realm = opts.realm
      if (opts.signal) args.signal = opts.signal
      if (opts.topic) args.topic = opts.topic
      if (opts.summary !== undefined) args.summary = opts.summary
      if (opts.importance !== undefined) args.importance = opts.importance
      const res = await api.call<{ id: string }>('add_cell', args)
      // Realm counts changed (possibly a brand-new realm) — drop the cached list.
      useRealmsStore().invalidate()
      this.cache.delete(res.id)
      await this.load(res.id)
      return res
    },
    // Append-only edit: revise_cell closes the current version and inserts a new one,
    // returning { old_id, new_id }. We re-fetch the new revision (falling back to an
    // optimistic clone of the previous entry) and make it the current cell, keeping the
    // previous revision in cache so history is never destroyed client-side.
    async revise(oldId: string, opts: { content: string; summary?: string }): Promise<{ old_id: string; new_id: string }> {
      const api = useApi()
      const res = await api.call<{ old_id: string; new_id: string }>('revise_cell', {
        old_id: oldId,
        new_content: opts.content,
        ...(opts.summary !== undefined ? { new_summary: opts.summary } : {})
      })
      const prev = this.cache.get(oldId)
      let newCell = await api.call<Cell>('get_cell', { cell_id: res.new_id }).catch(() => null)
      if (!newCell && prev) {
        newCell = { ...prev.cell, id: res.new_id, content: opts.content, summary: opts.summary ?? prev.cell.summary }
      }
      if (newCell) {
        this.store(res.new_id, { cell: newCell, facts: prev?.facts ?? [], tunnels: prev?.tunnels ?? [] })
        this.currentId = res.new_id
      }
      return res
    },
    // Move a cache hit to the back of the Map's insertion order so the size-capped
    // cache evicts least-recently-used entries instead of oldest-inserted (L-F10).
    touch(id: string) {
      const entry = this.cache.get(id)
      if (entry) {
        this.cache.delete(id)
        this.cache.set(id, entry)
      }
    },
    store(id: string, entry: CellEntry) {
      this.cache.delete(id) // re-insert at the back (freshest)
      this.cache.set(id, entry)
      if (this.cache.size > 50) {
        // Evict the least-recently-used entry, but never the currently-displayed
        // cell or the one just stored (L-F10).
        for (const key of this.cache.keys()) {
          if (key !== this.currentId && key !== id) {
            this.cache.delete(key)
            break
          }
        }
      }
    },
    clear() { this.currentId = null; this.selectedScores = null }
  }
})
