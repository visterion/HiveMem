import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCellStore } from '../../src/stores/cell'
import { resetApi, useApi } from '../../src/api/useApi'
import type { Cell } from '../../src/api/types'

describe('cell store revise()', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
  })

  it('creates a new revision (new id) and makes it current with the new content', async () => {
    const store = useCellStore()
    const api = useApi()
    const results = await api.call<Cell[]>('search', { query: '' })
    const orig = results[0]
    await store.load(orig.id)
    expect(store.currentId).toBe(orig.id)

    const res = await store.revise(orig.id, { content: 'revised body text' })

    expect(res.new_id).toBeTruthy()
    expect(res.new_id).not.toBe(orig.id)
    expect(store.currentId).toBe(res.new_id)
    expect(store.current?.cell.content).toBe('revised body text')
  })

  it('preserves the previous revision (append-only history)', async () => {
    const store = useCellStore()
    const api = useApi()
    const results = await api.call<Cell[]>('search', { query: '' })
    const orig = results[0]
    await store.load(orig.id)

    await store.revise(orig.id, { content: 'v2 content' })

    // the original revision is still retrievable by its id
    const old = await api.call<Cell>('get_cell', { cell_id: orig.id })
    expect(old.id).toBe(orig.id)
  })

  it('passes new_summary through when provided', async () => {
    const store = useCellStore()
    const api = useApi()
    const results = await api.call<Cell[]>('search', { query: '' })
    const orig = results[0]
    await store.load(orig.id)

    const res = await store.revise(orig.id, { content: 'body', summary: 'fresh summary' })
    const revised = await api.call<Cell>('get_cell', { cell_id: res.new_id })
    expect(revised.summary).toBe('fresh summary')
  })
})
