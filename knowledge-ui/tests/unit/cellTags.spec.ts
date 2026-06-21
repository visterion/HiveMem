import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCellStore } from '../../src/stores/cell'
import { resetApi, useApi } from '../../src/api/useApi'
import type { Cell } from '../../src/api/types'

describe('cell store tag editing', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
  })

  async function loadFirst() {
    const store = useCellStore()
    const results = await useApi().call<Cell[]>('search', { query: '' })
    await store.load(results[0].id)
    return store
  }

  it('addTags adds tags to the current cell (deduped)', async () => {
    const store = await loadFirst()
    const before = store.current!.cell.tags.length
    await store.addTags(store.currentId!, ['alpha', 'beta'])
    expect(store.current!.cell.tags).toContain('alpha')
    expect(store.current!.cell.tags).toContain('beta')
    // adding an existing tag again does not duplicate it
    await store.addTags(store.currentId!, ['alpha'])
    expect(store.current!.cell.tags.filter(t => t === 'alpha')).toHaveLength(1)
    expect(store.current!.cell.tags.length).toBeGreaterThanOrEqual(before + 2)
  })

  it('removeTags removes a tag from the current cell', async () => {
    const store = await loadFirst()
    await store.addTags(store.currentId!, ['gamma'])
    expect(store.current!.cell.tags).toContain('gamma')
    await store.removeTags(store.currentId!, ['gamma'])
    expect(store.current!.cell.tags).not.toContain('gamma')
  })
})
