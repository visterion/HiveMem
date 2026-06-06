import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApi } from '../../src/api/useApi'
import { MockApiClient } from '../../src/api/mockClient'
import { useCellStore } from '../../src/stores/cell'
import type { Cell } from '../../src/api/types'

function bareCell(id: string): Cell {
  return {
    id, realm: 'documents', signal: 'facts', topic: 'invoices',
    title: '', content: '[page=1] scan', summary: 'HUK invoice',
    key_points: [], insight: null, tags: [], importance: 1,
    status: 'committed', created_by: 'system',
    created_at: '2026-06-06T00:00:00Z', valid_from: '2026-06-06T00:00:00Z', valid_until: null,
  }
}

describe('cell store ensureAttachments', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
  })

  it('fetches attachments via get_cell and merges them into the cached entry', async () => {
    const store = useCellStore()
    await store.open(bareCell('c1'))
    expect(store.current?.cell.attachments).toBeUndefined()

    const spy = vi.spyOn(MockApiClient.prototype, 'call').mockResolvedValue({
      attachments: [{ id: 'a1', mime_type: 'application/pdf', original_filename: 'huk.pdf', size_bytes: 10 }],
    } as any)

    await store.ensureAttachments('c1')

    expect(spy).toHaveBeenCalledWith('get_cell', { cell_id: 'c1' })
    expect(store.current?.cell.attachments).toEqual([
      { id: 'a1', mime_type: 'application/pdf', original_filename: 'huk.pdf', size_bytes: 10 },
    ])
    spy.mockRestore()
  })

  it('is a no-op when attachments are already present', async () => {
    const store = useCellStore()
    const cell = bareCell('c2')
    cell.attachments = []
    await store.open(cell)

    const spy = vi.spyOn(MockApiClient.prototype, 'call')
    await store.ensureAttachments('c2')

    expect(spy).not.toHaveBeenCalled()
    spy.mockRestore()
  })

  it('leaves attachments undefined when the fetch fails', async () => {
    const store = useCellStore()
    await store.open(bareCell('c3'))

    const spy = vi.spyOn(MockApiClient.prototype, 'call').mockRejectedValue(new Error('network'))
    await store.ensureAttachments('c3')

    expect(store.current?.cell.attachments).toBeUndefined()
    spy.mockRestore()
  })
})
