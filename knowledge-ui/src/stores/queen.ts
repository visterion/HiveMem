import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import type { QueenRun, QueenRunList, QueenRunDetail, PendingApproval } from '../api/types'

export const useQueenStore = defineStore('queen', {
  state: () => ({
    runs: [] as QueenRun[],
    total: 0,
    costAvailable: false,
    unavailable: false,
    selectedRun: null as QueenRunDetail | null,
    pending: [] as PendingApproval[],
    loading: false,
  }),
  actions: {
    async refresh() {
      this.loading = true
      try {
        const api = useApi()
        const list = await api.call<QueenRunList>('queen_runs')
        this.runs = list.items
        this.total = list.total
        this.costAvailable = list.costAvailable
        this.unavailable = !!list.unavailable
        const pending = await api.call<PendingApproval[]>('pending_approvals')
        this.pending = pending.filter(p => p.created_by === 'queen')
      } finally {
        this.loading = false
      }
    },
    async selectRun(runId: string) {
      this.selectedRun = await useApi().call<QueenRunDetail>('queen_run_detail', { run_id: runId })
    },
    async approve(id: string, approved: boolean) {
      // Backend `approve_pending` expects a UUID list + a decision enum, not {id, approved}.
      await useApi().call('approve_pending', { ids: [id], decision: approved ? 'committed' : 'rejected' })
      this.pending = this.pending.filter(p => p.id !== id)
    },
  },
})
