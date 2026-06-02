import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApi } from '../../src/api/useApi'
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
})
