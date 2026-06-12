import { describe, it, expect } from 'vitest'
import { bucketKeyFor, groupPhotos } from '../../src/composables/photoGroups'

const NOW = new Date('2026-06-12T12:00:00Z')

describe('bucketKeyFor', () => {
  it('today / week / month / older buckets', () => {
    expect(bucketKeyFor('2026-06-12T08:00:00Z', NOW)).toBe('today')
    expect(bucketKeyFor('2026-06-09T08:00:00Z', NOW)).toBe('week')   // within 7 days, not today
    expect(bucketKeyFor('2026-06-02T08:00:00Z', NOW)).toBe('month')  // same calendar month, >7d
    expect(bucketKeyFor('2026-05-20T08:00:00Z', NOW)).toBe('2026-05')
    expect(bucketKeyFor('2026-04-01T08:00:00Z', NOW)).toBe('2026-04')
  })
})

describe('groupPhotos', () => {
  it('groups by effective date (taken_at ?? created_at), newest bucket first, preserves item order', () => {
    const items = [
      { id: 'a', taken_at: '2026-06-12T09:00:00Z', created_at: '2026-06-12T09:00:00Z' },
      { id: 'b', taken_at: null, created_at: '2026-06-12T08:00:00Z' },          // today via created_at
      { id: 'c', taken_at: '2026-05-20T08:00:00Z', created_at: '2026-05-20T08:00:00Z' },
    ]
    const groups = groupPhotos(items, NOW)
    expect(groups.map(g => g.key)).toEqual(['today', '2026-05'])
    expect(groups[0].items.map(i => i.id)).toEqual(['a', 'b'])
    expect(groups[1].items.map(i => i.id)).toEqual(['c'])
  })
})
