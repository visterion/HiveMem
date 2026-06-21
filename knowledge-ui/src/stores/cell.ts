import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import type { Cell, Fact, Tunnel, SearchResult } from '../api/types'
import type { ParsedNote } from '../composables/obsidianImport'

type CellEntry = { cell: Cell; facts: Fact[]; tunnels: Tunnel[] }

// Cells have no `title`; facts are keyed on a human subject. Use summary/topic when
// available, and skip the lookup for bare cells (e.g. scans) so a blank subject never
// triggers a "Missing subject" error. Module-level helper to keep the store's `this`
// typing simple (no cross-action calls).
async function fetchFacts(cell: Cell): Promise<Fact[]> {
  const subject = cell.summary?.trim() || cell.topic?.trim()
  if (!subject) return []
  return useApi().call<Fact[]>('quick_facts', { subject }).catch(() => [])
}

export const useCellStore = defineStore('cell', {
  state: () => ({
    cache: new Map<string, CellEntry>(),
    currentId: null as string | null,
    loading: false,
    selectedScores: null as SearchResult | null
  }),
  getters: {
    current(s): CellEntry | null {
      return s.currentId ? s.cache.get(s.currentId) ?? null : null
    }
  },
  actions: {
    async load(id: string) {
      this.loading = true
      try {
        if (!this.cache.has(id)) {
          const api = useApi()
          const [cell, tunnels] = await Promise.all([
            api.call<Cell>('get_cell', { cell_id: id }),
            api.call<Tunnel[]>('traverse', { cell_id: id, depth: 1 }).catch(() => [])
          ])
          const facts = await fetchFacts(cell)
          this.store(id, { cell, facts, tunnels })
        }
        this.currentId = id
      } finally { this.loading = false }
    },
    // Open a cell using an already-fetched (rich) row — e.g. a search result that
    // carries content/summary. Avoids a second get_cell that would drop those fields,
    // so the panel shows a real label and the OCR/parsed content immediately.
    async open(cell: Cell) {
      this.selectedScores = (cell && 'score_total' in cell) ? (cell as unknown as SearchResult) : null
      this.loading = true
      try {
        const id = cell.id
        if (!this.cache.has(id)) {
          const tunnels = await useApi().call<Tunnel[]>('traverse', { cell_id: id, depth: 1 }).catch(() => [])
          const facts = await fetchFacts(cell)
          this.store(id, { cell, facts, tunnels })
        }
        this.currentId = id
      } finally { this.loading = false }
    },
    // Attachments are not carried by search rows (used by open()) and are only
    // needed when the reader is shown, so fetch them lazily via get_cell (which
    // returns them by default) and merge into the cached entry. Idempotent.
    async ensureAttachments(id: string) {
      const entry = this.cache.get(id)
      if (!entry || entry.cell.attachments !== undefined) return
      const full = await useApi().call<Cell>('get_cell', { cell_id: id }).catch(() => null)
      const current = this.cache.get(id)
      if (full && current) {
        current.cell.attachments = full.attachments ?? []
      }
    },
    // Bulk-import parsed Obsidian notes: one add_cell per note (default realm when the
    // note has none), stub cells for [[wiki-links]] whose target isn't in the vault, then
    // an add_tunnel per link. Title→id mapping resolves links to the cells just created.
    async importObsidian(
      notes: ParsedNote[],
      opts: { defaultRealm: string; onProgress?: (done: number, total: number) => void }
    ): Promise<{ cellsCreated: number; tunnelsCreated: number; stubsCreated: number }> {
      const api = useApi()
      const titleToId = new Map<string, string>()
      const total = notes.length
      let done = 0
      for (const note of notes) {
        const res = await api.call<{ id: string }>('add_cell', {
          content: note.content,
          realm: note.realm || opts.defaultRealm,
          ...(note.signal ? { signal: note.signal } : {}),
          topic: note.title,
          ...(note.tags.length ? { tags: note.tags } : {}),
          ...(note.validFrom ? { valid_from: note.validFrom } : {})
        })
        titleToId.set(note.title, res.id)
        opts.onProgress?.(++done, total)
      }
      let stubsCreated = 0
      let tunnelsCreated = 0
      for (const note of notes) {
        const fromId = titleToId.get(note.title)
        if (!fromId) continue
        for (const link of note.links) {
          let targetId = titleToId.get(link)
          if (!targetId) {
            const stub = await api.call<{ id: string }>('add_cell', { content: link, topic: link, realm: opts.defaultRealm })
            targetId = stub.id
            titleToId.set(link, targetId)
            stubsCreated++
          }
          await api.call('add_tunnel', { from_cell: fromId, to_cell: targetId, relation: 'related_to' })
          tunnelsCreated++
        }
      }
      return { cellsCreated: notes.length, tunnelsCreated, stubsCreated }
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
    // Inline tag editing via add_tags/remove_tags. The cached cell's tags are updated
    // optimistically (union / minus) so the reader reflects the change without a re-fetch.
    async addTags(id: string, tags: string[]): Promise<void> {
      if (!tags.length) return
      await useApi().call('add_tags', { cell_id: id, tags })
      const entry = this.cache.get(id)
      if (entry) {
        const merged = new Set([...(entry.cell.tags ?? []), ...tags])
        entry.cell.tags = [...merged]
      }
    },
    async removeTags(id: string, tags: string[]): Promise<void> {
      if (!tags.length) return
      await useApi().call('remove_tags', { cell_id: id, tags })
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
    store(id: string, entry: CellEntry) {
      this.cache.set(id, entry)
      if (this.cache.size > 50) {
        const first = this.cache.keys().next().value
        if (first) this.cache.delete(first)
      }
    },
    clear() { this.currentId = null; this.selectedScores = null }
  }
})
