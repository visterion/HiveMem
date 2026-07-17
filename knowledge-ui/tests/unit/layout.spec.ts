import { describe, it, expect } from 'vitest'
import { computeWingPositions, poissonDiskCells } from '../../src/composables/layout'
import type { Realm, Cell, Tunnel } from '../../src/api/types'

describe('layout', () => {
  it('realm positions settle to non-overlapping centres', () => {
    const realms: Realm[] = [
      { name: 'a', cell_count: 10, signals: [] },
      { name: 'b', cell_count: 20, signals: [] },
      { name: 'c', cell_count: 5, signals: [] }
    ]
    const pos = computeWingPositions(realms, [] as Cell[], [] as Tunnel[], { width: 1000, height: 800 })
    expect(pos.size).toBe(3)
  })

  it('does not throw when a null-realm cell tunnels to a classified cell', () => {
    const realms: Realm[] = [
      { name: 'a', cell_count: 1, signals: [] }
    ]
    const cellBase = {
      title: '', content: '', summary: null, key_points: [], insight: null,
      tags: [], importance: 1 as const, status: 'committed' as const,
      created_by: 'test', created_at: '2026-01-01T00:00:00Z', valid_from: '2026-01-01T00:00:00Z',
      valid_until: null, signal: null, topic: null
    }
    const cells: Cell[] = [
      { ...cellBase, id: 'unclassified-1', realm: null },
      { ...cellBase, id: 'classified-1', realm: 'a' }
    ]
    const tunnels: Tunnel[] = [
      {
        id: 't1', from_cell: 'unclassified-1', to_cell: 'classified-1',
        relation: 'related_to', note: null, status: 'committed',
        created_at: '2026-01-01T00:00:00Z', valid_until: null
      }
    ]
    expect(() => computeWingPositions(realms, cells, tunnels, { width: 1000, height: 800 })).not.toThrow()
  })

  it('poissonDiskCells emits N points inside radius with min spacing', () => {
    const pts = poissonDiskCells(20, { x: 100, y: 100, r: 80, minDist: 12, seed: 'realm-a' })
    expect(pts.length).toBe(20)
    for (const p of pts) expect(Math.hypot(p.x - 100, p.y - 100)).toBeLessThanOrEqual(80)
  })
})
