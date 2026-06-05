import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import type { Cell, Fact, Tunnel } from '../api/types'

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
    loading: false
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
    store(id: string, entry: CellEntry) {
      this.cache.set(id, entry)
      if (this.cache.size > 50) {
        const first = this.cache.keys().next().value
        if (first) this.cache.delete(first)
      }
    },
    clear() { this.currentId = null }
  }
})
