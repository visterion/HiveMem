import { describe, it, expect } from 'vitest'
import { MockApiClient } from '../../src/api/mockClient'
import type { StatusSummary, Cell } from '../../src/api/types'

describe('MockApiClient', () => {
  it('returns deterministic status', async () => {
    const c = new MockApiClient()
    const s = await c.call<StatusSummary>('status')
    expect(s.cell_count).toBeGreaterThan(0)
    expect(s.realm_count).toBeGreaterThan(0)
    expect(typeof s.last_activity).toBe('string')
  })

  it('search returns cells array', async () => {
    const c = new MockApiClient()
    const res = await c.call<Cell[]>('search', { query: '' })
    expect(Array.isArray(res)).toBe(true)
    expect(res.length).toBeGreaterThan(0)
  })

  it('get_cell returns cell with matching id', async () => {
    const c = new MockApiClient()
    const all = await c.call<Cell[]>('search', { query: '' })
    const cell = await c.call<Cell>('get_cell', { cell_id: all[0].id })
    expect(cell.id).toBe(all[0].id)
  })

  it('subscribe returns a working unsubscribe and ticker stays silent', async () => {
    // Streaming moved to the hivemem_stream_next long-poll; the subscribe ticker is
    // intentionally silent (see MockApiClient.startTicker). Subscribers attach without
    // error and receive no synthetic events.
    const c = new MockApiClient({ eventInterval: 10 })
    const events: string[] = []
    const unsub = c.subscribe(e => events.push(e.type))
    expect(typeof unsub).toBe('function')
    await new Promise(r => setTimeout(r, 50))
    unsub()
    expect(events.length).toBe(0)
  })
})
