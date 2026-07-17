import { describe, it, expect } from 'vitest'
import { colorForRealm, colorForRelation } from '../../src/graph/colors'
import { paletteForRealm } from '../../src/composables/realmPalette'

describe('graph colors', () => {
  it('maps known realms to their token hex', () => {
    expect(colorForRealm('documents')).toBe('#4FB3F0')
    expect(colorForRealm('legal')).toBe('#F4B740')
    expect(colorForRealm('engineering')).toBe('#9BCB3C')
    expect(colorForRealm('codebase')).toBe('#A781F2')
    expect(colorForRealm('finance')).toBe('#46C08A')
  })
  it('gives unknown realms a stable hex fallback', () => {
    const a = colorForRealm('Distributed Systems')
    expect(a).toBe(colorForRealm('Distributed Systems'))
    expect(a.startsWith('#')).toBe(true)
  })
  it('is null/undefined safe', () => {
    expect(colorForRealm(null).startsWith('#')).toBe(true)
    expect(colorForRealm(undefined).startsWith('#')).toBe(true)
  })
  it('does not paint a null realm like a hashed realm (Finding 4)', () => {
    // Before the fix, colorForRealm(null) fell through to paletteForRealm(0).base —
    // indistinguishable from whatever real realm name happens to hash to index 0 —
    // so an unclassified node looked classified on the force graph.
    const hashedZero = paletteForRealm(0).base
    expect(colorForRealm(null)).not.toBe(hashedZero)
    expect(colorForRealm(undefined)).not.toBe(hashedZero)
    // Must agree with realmColorFor(null)'s neutral intent (var(--text-2)), just as
    // a literal hex since ctx.fillStyle can't resolve CSS custom properties.
    expect(colorForRealm(null)).toBe('#6B7385')
  })
  it('colors tunnels in the cyan family, contradicts red', () => {
    expect(colorForRelation('related_to')).toBe('#46D6E0')
    expect(colorForRelation('builds_on')).toBe('#5BD6CF')
    expect(colorForRelation('refines')).toBe('#6FB6E8')
    expect(colorForRelation('contradicts')).toBe('#F0676B')
  })
  it('falls back to cyan for unknown relations', () => {
    expect(colorForRelation('unknown_relation')).toBe('#46D6E0')
  })
})
