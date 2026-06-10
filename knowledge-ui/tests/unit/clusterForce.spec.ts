import { describe, it, expect } from 'vitest'
import { realmClusterCenters, neighborIds, shouldShowLabel, LABEL_ZOOM } from '../../src/graph/clusterForce'
import type { GraphLink } from '../../src/graph/types'

describe('realmClusterCenters', () => {
  it('places N unique realms at N distinct centers on a ring (dupes collapse)', () => {
    const c = realmClusterCenters(['a', 'b', 'c', 'a'], 100)
    expect(Object.keys(c).sort()).toEqual(['a', 'b', 'c'])
    const pts = Object.values(c).map(p => `${p.x.toFixed(2)},${p.y.toFixed(2)}`)
    expect(new Set(pts).size).toBe(3)
  })
  it('is deterministic across calls', () => {
    expect(realmClusterCenters(['x', 'y'])).toEqual(realmClusterCenters(['x', 'y']))
  })
  it('centers a single realm at the origin', () => {
    expect(realmClusterCenters(['solo'])).toEqual({ solo: { x: 0, y: 0 } })
  })
  it('returns empty for no realms', () => {
    expect(realmClusterCenters([])).toEqual({})
  })
})

describe('neighborIds', () => {
  const links = [
    { id: 'l1', source: 'a', target: 'b', relation: 'related_to', color: '' },
    { id: 'l2', source: 'c', target: 'a', relation: 'related_to', color: '' },
  ] as GraphLink[]
  it('includes the active id and its direct partners', () => {
    expect([...neighborIds('a', links)].sort()).toEqual(['a', 'b', 'c'])
  })
  it('handles object-shaped link ends (post-simulation)', () => {
    const objLinks = [
      { id: 'l1', source: { id: 'a' }, target: { id: 'b' }, relation: 'related_to', color: '' },
    ] as unknown as GraphLink[]
    expect([...neighborIds('a', objLinks)].sort()).toEqual(['a', 'b'])
  })
  it('returns an empty set for a null active id', () => {
    expect(neighborIds(null, links).size).toBe(0)
  })
})

describe('shouldShowLabel', () => {
  const hi = new Set(['a'])
  it('shows all labels at/above the zoom threshold', () => {
    expect(shouldShowLabel('z', { globalScale: LABEL_ZOOM, highlightIds: new Set(), hasActive: false })).toBe(true)
  })
  it('shows highlighted labels when active, below zoom', () => {
    expect(shouldShowLabel('a', { globalScale: 1, highlightIds: hi, hasActive: true })).toBe(true)
    expect(shouldShowLabel('b', { globalScale: 1, highlightIds: hi, hasActive: true })).toBe(false)
  })
  it('hides labels below zoom when nothing is active', () => {
    expect(shouldShowLabel('a', { globalScale: 1, highlightIds: new Set(), hasActive: false })).toBe(false)
  })
})
