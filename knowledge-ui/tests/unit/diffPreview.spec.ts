import { describe, it, expect } from 'vitest'
import { computeLineDiff } from '../../src/composables/diffPreview'

describe('computeLineDiff', () => {
  it('reports added and removed line counts', () => {
    const d = computeLineDiff('a\nb\nc\n', 'a\nB\nc\n')
    expect(d.added).toBe(1)
    expect(d.removed).toBe(1)
  })

  it('marks added and removed parts distinctly', () => {
    const d = computeLineDiff('keep\nold\n', 'keep\nnew\n')
    expect(d.parts.some(p => p.type === 'del' && p.value.includes('old'))).toBe(true)
    expect(d.parts.some(p => p.type === 'add' && p.value.includes('new'))).toBe(true)
    expect(d.parts.some(p => p.type === 'context' && p.value.includes('keep'))).toBe(true)
  })

  it('returns zero counts and no changes when text is identical', () => {
    const d = computeLineDiff('same\ntext\n', 'same\ntext\n')
    expect(d.added).toBe(0)
    expect(d.removed).toBe(0)
    expect(d.changed).toBe(false)
  })

  it('counts pure additions', () => {
    const d = computeLineDiff('a\n', 'a\nb\nc\n')
    expect(d.added).toBe(2)
    expect(d.removed).toBe(0)
    expect(d.changed).toBe(true)
  })
})
