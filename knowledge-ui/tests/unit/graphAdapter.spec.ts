import { describe, it, expect } from 'vitest'
import type { Cell, Tunnel } from '../../src/api/types'
import { colorForRealm, colorForRelation } from '../../src/graph/colors'
import { mapCanvasToForceGraph } from '../../src/graph/mapCanvasToForceGraph'

describe('mapCanvasToForceGraph', () => {
  it('maps cells and tunnels into node/link structures', () => {
    const cells: Cell[] = [
      {
        id: 'a',
        realm: 'Systems',
        signal: 'Consensus',
        topic: 'Architecture',
        title: 'A',
        content: '',
        summary: null,
        key_points: [],
        insight: null,
        tags: [],
        importance: 3,
        status: 'committed',
        created_by: 'tester',
        created_at: '2026-04-22T00:00:00Z',
        valid_from: '2026-04-22T00:00:00Z',
        valid_until: null
      },
      {
        id: 'b',
        realm: 'Systems',
        signal: null,
        topic: null,
        title: 'B',
        content: '',
        summary: null,
        key_points: [],
        insight: null,
        tags: [],
        importance: 1,
        status: 'committed',
        created_by: 'tester',
        created_at: '2026-04-22T00:00:00Z',
        valid_from: '2026-04-22T00:00:00Z',
        valid_until: null
      },
      {
        id: 'c',
        realm: 'Systems',
        signal: null,
        topic: null,
        title: 'C',
        content: '',
        summary: null,
        key_points: [],
        insight: null,
        tags: [],
        importance: 2,
        status: 'committed',
        created_by: 'tester',
        created_at: '2026-04-22T00:00:00Z',
        valid_from: '2026-04-22T00:00:00Z',
        valid_until: null
      }
    ]

    const tunnels: Tunnel[] = [
      {
        id: 't1',
        from_cell: 'a',
        to_cell: 'b',
        relation: 'builds_on',
        note: null,
        status: 'committed',
        created_at: '2026-04-22T00:00:00Z',
        valid_until: null
      },
      {
        id: 't2',
        from_cell: 'a',
        to_cell: 'missing',
        relation: 'contradicts',
        note: null,
        status: 'committed',
        created_at: '2026-04-22T00:00:00Z',
        valid_until: null
      }
    ]

    const result = mapCanvasToForceGraph({
      cells,
      tunnels
    })

    expect(result.nodes).toHaveLength(3)
    expect(result.nodes).toEqual(expect.arrayContaining([
      expect.objectContaining({
        id: 'a',
        signal: 'Consensus',
        topic: 'Architecture',
        val: 4.5,
        color: colorForRealm('Systems')
      }),
      expect.objectContaining({
        id: 'b',
        signal: null,
        topic: null,
        val: 3.5,
        color: colorForRealm('Systems')
      }),
      expect.objectContaining({
        id: 'c',
        signal: null,
        topic: null,
        val: 2,
        color: colorForRealm('Systems')
      })
    ]))
    expect(result.links).toEqual([
      expect.objectContaining({
        source: 'a',
        target: 'b',
        relation: 'builds_on',
        color: colorForRelation('builds_on')
      })
    ])
  })

  it('defensively normalizes malformed low importance values', () => {
    const result = mapCanvasToForceGraph({
      cells: [
        { id: 'a', title: 'A', realm: 'Systems', signal: null, topic: null, importance: 0 }
      ] as any,
      tunnels: []
    })

    expect(result.nodes).toEqual([
      expect.objectContaining({ id: 'a', importance: 0, val: 2 })
    ])
  })

  it('returns the cyan fallback color for unknown relations', () => {
    expect(colorForRelation('unknown_relation')).toBe('#46D6E0')
  })
})
