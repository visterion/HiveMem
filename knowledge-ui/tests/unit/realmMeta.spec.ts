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
