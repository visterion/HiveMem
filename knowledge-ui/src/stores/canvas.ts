import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import type { Cell, Realm, Tunnel } from '../api/types'

interface StreamResponse { cells: Cell[]; tunnels: Tunnel[]; done: boolean }

export const useCanvasStore = defineStore('canvas', {
  state: () => ({
    realms: [] as Realm[],
    cells: [] as Cell[],
    tunnels: [] as Tunnel[],
    loaded: false,
    zoom: 1,
    pan: { x: 0, y: 0 },
    focusedId: null as string | null,
    hoveredId: null as string | null,
    streamActive: false,
    _streamAbort: false,
    _loadingTopLevel: false,
  }),
  actions: {
    async loadTopLevel() {
      // Guard against double-runs (e.g. two routes mounting in quick succession):
      // a second call while the first load or its stream is still active would
      // append every cell/tunnel a second time (M56).
      if (this._loadingTopLevel || this.streamActive) return
      this._loadingTopLevel = true
      try {
        const api = useApi()
        const rows = await api.call<Array<{ value: string; label?: string; cell_count: number }>>('list')
        this.realms = rows.map(r => ({ name: r.value, cell_count: r.cell_count, signals: [] }))
        this.cells = []
        this.tunnels = []
        this.loaded = true
        this._streamAbort = false
        void this._longPoll() // sets streamActive synchronously before first await
      } finally {
        this._loadingTopLevel = false
      }
    },
    async _longPoll() {
      this.streamActive = true
      try {
        // Mock mode: the real /api/gui/stream endpoint doesn't exist, so drive the
        // graph from the mock client's hivemem_stream_next long-poll (one batch per
        // call until done) — gives a progressive reveal in local dev.
        if (localStorage.getItem('hivemem_mock') === 'true') {
          const api = useApi()
          let done = false
          while (!done && !this._streamAbort) {
            const resp = await api.call<StreamResponse>('hivemem_stream_next', { timeout_ms: 25000 })
            if (resp.cells?.length) this.cells = [...this.cells, ...resp.cells]
            if (resp.tunnels?.length) this.tunnels = [...this.tunnels, ...resp.tunnels]
            done = resp.done
          }
          return
        }
        const res = await fetch('/api/gui/stream', { credentials: 'same-origin' })
        if (res.status === 401) { window.location.href = '/login'; return }
        if (!res.ok) return
        const resp = await res.json() as StreamResponse
        if (resp.cells?.length) this.cells = [...this.cells, ...resp.cells]
        if (resp.tunnels?.length) this.tunnels = [...this.tunnels, ...resp.tunnels]
      } finally {
        this.streamActive = false
      }
    },
    stopStream() { this._streamAbort = true },
    setFocus(id: string | null) { this.focusedId = id },
    setHover(id: string | null) { this.hoveredId = id }
  }
})
