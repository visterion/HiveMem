import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCellStore } from '../../src/stores/cell'
import { resetApi, useApi } from '../../src/api/useApi'
import type { Cell } from '../../src/api/types'

describe('cell store addCell()', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
  })

  it('creates a cell and makes it the current cell', async () => {
    const store = useCellStore()
    const res = await store.addCell({ content: 'brand new note', realm: 'engineering', signal: 'facts', topic: 'my-topic' })

    expect(res.id).toBeTruthy()
    expect(store.currentId).toBe(res.id)
    expect(store.current?.cell.content).toBe('brand new note')
    expect(store.current?.cell.realm).toBe('engineering')
    expect(store.current?.cell.signal).toBe('facts')
    expect(store.current?.cell.topic).toBe('my-topic')
  })

  it('passes the chosen layers through and the new cell is retrievable', async () => {
    const store = useCellStore()
    const res = await store.addCell({ content: 'body', realm: 'personal', summary: 'a summary' })
    const c = await useApi().call<Cell>('get_cell', { cell_id: res.id })
    expect(c.content).toBe('body')
    expect(c.realm).toBe('personal')
    expect(c.summary).toBe('a summary')
  })
})
