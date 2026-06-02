import { describe, expect, it } from 'vitest'
import { MockApiClient } from '../../src/api/mockClient'
import type { QueenRunList, QueenRunDetail, PendingApproval } from '../../src/api/types'

describe('MockApiClient queen tools', () => {
  it('returns a run list with costAvailable flag', async () => {
    const api = new MockApiClient({ latencyMs: [0, 0] })
    const out = await api.call<QueenRunList>('queen_runs')
    expect(out.items.length).toBeGreaterThan(0)
    expect(typeof out.costAvailable).toBe('boolean')
    expect(out.items[0].id).toBeTruthy()
  })

  it('returns run detail with an event timeline', async () => {
    const api = new MockApiClient({ latencyMs: [0, 0] })
    const list = await api.call<QueenRunList>('queen_runs')
    const detail = await api.call<QueenRunDetail>('queen_run_detail', { run_id: list.items[0].id })
    expect(Array.isArray(detail.events)).toBe(true)
    expect(detail.run).toBeTruthy()
  })

  it('returns pending queen proposals', async () => {
    const api = new MockApiClient({ latencyMs: [0, 0] })
    const pending = await api.call<PendingApproval[]>('pending_approvals')
    expect(pending.some(p => p.created_by === 'queen')).toBe(true)
  })
})
