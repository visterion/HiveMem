import { describe, it, expect } from 'vitest'
import { realmMetaFor, realmColorFor, REALM_META } from '../../src/composables/realmMeta'

describe('realmMeta', () => {
  it('returns metadata for a known realm', () => {
    const m = realmMetaFor('legal')
    expect(m.priv).toBe('local')
    expect(m.desc.length).toBeGreaterThan(0)
  })

  it('falls back to cloud/empty for an unknown realm', () => {
    const m = realmMetaFor('Distributed Systems')
    expect(m.priv).toBe('cloud')
    expect(m.desc).toBe('')
  })

  it('maps known realms to a CSS var color', () => {
    expect(realmColorFor('documents')).toBe('var(--r-docs)')
    expect(realmColorFor('legal')).toBe('var(--r-legal)')
    expect(realmColorFor('codebase')).toBe('var(--r-code)')
    expect(realmColorFor('engineering')).toBe('var(--r-eng)')
  })

  it('returns a stable hashed hex color for unknown realms', () => {
    const a = realmColorFor('Databases')
    const b = realmColorFor('Databases')
    expect(a).toBe(b)
    expect(a.startsWith('#')).toBe(true)
  })

  it('covers exactly the 8 SP-A realm vars', () => {
    expect(Object.keys(REALM_META).sort()).toEqual(
      ['codebase', 'documents', 'engineering', 'finance', 'legal', 'medical', 'private', 'work'])
  })
})

describe('realmColorFor with no realm', () => {
  it('returns a colour for null instead of throwing', () => {
    expect(() => realmColorFor(null)).not.toThrow()
    expect(realmColorFor(null)).toBeTypeOf('string')
    expect(realmColorFor(null).length).toBeGreaterThan(0)
  })

  it('returns the same neutral colour for null, undefined and empty string', () => {
    expect(realmColorFor(undefined)).toBe(realmColorFor(null))
    expect(realmColorFor('')).toBe(realmColorFor(null))
  })

  it('still maps a known realm to its CSS var', () => {
    expect(realmColorFor('documents')).toBe('var(--r-docs)')
  })

  it('still hashes an unknown realm to a stable colour', () => {
    expect(realmColorFor('hivemem')).toBe(realmColorFor('hivemem'))
    expect(realmColorFor('hivemem')).not.toBe(realmColorFor(null))
  })
})

describe('realmMetaFor with no realm', () => {
  it('does not throw on null', () => {
    expect(() => realmMetaFor(null)).not.toThrow()
    expect(realmMetaFor(null).priv).toBe('cloud')
  })
})
