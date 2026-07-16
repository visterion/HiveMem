import { setActivePinia, createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useQueenStore } from '../../src/stores/queen'

const call = vi.fn()
vi.mock('../../src/api/useApi', () => ({ useApi: () => ({ call }) }))

describe('queen store archivist log', () => {
  beforeEach(() => { setActivePinia(createPinia()); call.mockReset() })

  it('loadArchivistLog stores entries', async () => {
    call.mockResolvedValueOnce({ entries: [
      { op_type: 'reclassify_cell', at: 't', cell_id: 'c1', reason: 'r',
        agent_id: 'inbox-archivist', old_realm: 'inbox', new_realm: 'work' },
    ] })
    const store = useQueenStore()
    await store.loadArchivistLog()
    expect(call).toHaveBeenCalledWith('archivist_log')
    expect(store.archivistLog).toHaveLength(1)
    expect(store.archivistLog[0].new_realm).toBe('work')
  })
})
