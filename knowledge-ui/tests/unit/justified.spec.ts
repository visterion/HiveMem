import { describe, it, expect } from 'vitest'
import { useJustifiedRows, aspectOf } from '../../src/composables/justified'

type Item = { width: number | null; height: number | null }
const square = (n: number): Item[] => Array.from({ length: n }, () => ({ width: 100, height: 100 }))

describe('aspectOf', () => {
  it('returns width/height for valid dims and 1.5 fallback for null/zero', () => {
    expect(aspectOf(300, 100)).toBe(3)
    expect(aspectOf(null, 100)).toBe(1.5)
    expect(aspectOf(0, 0)).toBe(1.5)
  })
})

describe('useJustifiedRows', () => {
  it('returns empty for zero width or no items', () => {
    expect(useJustifiedRows(square(4), 0)).toEqual([])
    expect(useJustifiedRows([], 800)).toEqual([])
  })

  it('packs full rows to (nearly) the container width', () => {
    const rows = useJustifiedRows(square(12), 800, 132, 4)
    for (const row of rows.slice(0, -1)) {
      const totalW = row.items.reduce((s, c) => s + c.width, 0) + (row.items.length - 1) * 4
      expect(Math.abs(totalW - 800)).toBeLessThan(1)
      expect(row.height).toBeGreaterThan(0)
    }
  })

  it('keeps the last partial row at the target height', () => {
    // one wide item can never fill an 800px row at height 132 (3*132=396<800) → last row
    const rows = useJustifiedRows([{ width: 300, height: 100 }], 800, 132, 4)
    expect(rows).toHaveLength(1)
    expect(rows[0].height).toBe(132)
    expect(rows[0].items[0].width).toBeCloseTo(3 * 132, 5)
  })

  it('uses the 1.5 aspect fallback for null-dim items', () => {
    const rows = useJustifiedRows([{ width: null, height: null }], 800, 132, 4)
    expect(rows[0].items[0].width).toBeCloseTo(1.5 * 132, 5)
  })
})
