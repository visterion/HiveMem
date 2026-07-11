import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApi } from '../../src/api/useApi'
import { MockApiClient } from '../../src/api/mockClient'
import { useQueenStore } from '../../src/stores/queen'

describe('queen store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
  })

  it('loads runs and pending proposals via the mock client', async () => {
    const store = useQueenStore()
    await store.refresh()
    expect(store.runs.length).toBe(3)
    expect(store.costAvailable).toBe(true)
    expect(store.pending.length).toBe(2)
  })

  it('loads run detail on select', async () => {
    const store = useQueenStore()
    await store.refresh()
    await store.selectRun('run-001')
    expect(store.selectedRun?.run.id).toBe('run-001')
    expect(store.selectedRun?.events.length).toBeGreaterThan(0)
  })

  it('removes a proposal from the queue after approval', async () => {
    const store = useQueenStore()
    await store.refresh()
    await store.approve('p-1', true)
    expect(store.pending.find(p => p.id === 'p-1')).toBeUndefined()
  })

  it('calls approve_pending with the backend ids/decision contract', async () => {
    const spy = vi.spyOn(MockApiClient.prototype, 'call')
    const store = useQueenStore()
    await store.refresh()
    spy.mockClear()
    await store.approve('p-2', false)
    expect(spy).toHaveBeenCalledWith('approve_pending', { ids: ['p-2'], decision: 'rejected' })
    spy.mockRestore()
  })

  it('a slower earlier selectRun() cannot overwrite a later selection (E6)', async () => {
    let resolveFirst!: (v: unknown) => void
    let call = 0
    const spy = vi.spyOn(MockApiClient.prototype, 'call').mockImplementation((tool: string, args?: Record<string, unknown>) => {
      if (tool !== 'queen_run_detail') return Promise.resolve({})
      call++
      if (call === 1) return new Promise(res => { resolveFirst = res })
      return Promise.resolve({ run: { id: args?.run_id }, events: [{ id: 'e1' }] })
    })
    const store = useQueenStore()
    const p1 = store.selectRun('run-001') // slower
    const p2 = store.selectRun('run-002') // faster, resolves first
    await p2
    expect(store.selectedRun?.run.id).toBe('run-002')
    resolveFirst({ run: { id: 'run-001' }, events: [] })
    await p1
    expect(store.selectedRun?.run.id).toBe('run-002') // stale response must not win
    spy.mockRestore()
  })
})
