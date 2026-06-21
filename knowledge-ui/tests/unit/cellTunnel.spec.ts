import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCellStore } from '../../src/stores/cell'
import { resetApi, useApi } from '../../src/api/useApi'
import type { Cell } from '../../src/api/types'

describe('cell store addTunnel()', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
  })

  it('creates a tunnel and appends it to the source cell tunnels', async () => {
    const store = useCellStore()
    const results = await useApi().call<Cell[]>('search', { query: '' })
    const from = results[0]
    const to = results[1]
    await store.load(from.id)
    const before = store.current!.tunnels.length

    const res = await store.addTunnel(from.id, to.id, 'related_to', 'a note')

    expect(res.id).toBeTruthy()
    expect(res.relation).toBe('related_to')
    expect(store.current!.tunnels.length).toBe(before + 1)
    const added = store.current!.tunnels.find(t => t.id === res.id)
    expect(added?.to_cell).toBe(to.id)
    expect(added?.note).toBe('a note')
  })
})
