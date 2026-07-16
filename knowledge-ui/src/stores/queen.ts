import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import type { QueenRun, QueenRunList, QueenRunDetail, PendingApproval, ArchivistLogEntry } from '../api/types'

export const useQueenStore = defineStore('queen', {
  state: () => ({
    runs: [] as QueenRun[],
    total: 0,
    costAvailable: false,
    unavailable: false,
    selectedRun: null as QueenRunDetail | null,
    pending: [] as PendingApproval[],
    archivistLog: [] as ArchivistLogEntry[],
    loading: false,
    // Monotonic token: only the latest selectRun() may commit selectedRun, so a
    // slower earlier queen_run_detail response can't overwrite a later selection
    // (mirrors cell.ts/scans.ts's loadSeq guard).
    selectSeq: 0,
  }),
  actions: {
    async refresh() {
      this.loading = true
      try {
        const api = useApi()
        const [list, pending] = await Promise.all([
          api.call<QueenRunList>('queen_runs'),
          api.call<PendingApproval[]>('pending_approvals'),
        ])
        this.runs = list.items
        this.total = list.total
        this.costAvailable = list.costAvailable
        this.unavailable = !!list.unavailable
        this.pending = pending.filter(p => p.created_by === 'queen')
      } finally {
        this.loading = false
      }
    },
    async selectRun(runId: string) {
      const seq = ++this.selectSeq
      const detail = await useApi().call<QueenRunDetail>('queen_run_detail', { run_id: runId })
      if (seq !== this.selectSeq) return // stale — a newer selectRun() owns the state
      this.selectedRun = detail
    },
    async loadArchivistLog() {
      const res = await useApi().call<{ entries: ArchivistLogEntry[] }>('archivist_log')
      this.archivistLog = res.entries
    },
    async approve(id: string, approved: boolean) {
      // Backend `approve_pending` expects a UUID list + a decision enum, not {id, approved}.
      await useApi().call('approve_pending', { ids: [id], decision: approved ? 'committed' : 'rejected' })
      this.pending = this.pending.filter(p => p.id !== id)
    },
  },
})
