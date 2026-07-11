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

  it('uses the LOCAL calendar day, not the UTC one, for "today" (E6)', () => {
    // Force a timezone well ahead of UTC (UTC+14) so a photo/now pair that share
    // a LOCAL calendar day but straddle a UTC calendar-day boundary actually
    // exercises the discrepancy, regardless of whatever TZ the test runner
    // itself happens to be in.
    const originalTZ = process.env.TZ
    process.env.TZ = 'Pacific/Kiritimati' // UTC+14
    try {
      const now = new Date('2026-06-13T00:30:00Z')   // 2026-06-13 14:30 local (UTC+14)
      const photo = '2026-06-12T23:00:00Z'            // 2026-06-13 13:00 local (UTC+14) — same LOCAL day as now, previous UTC day
      // Comparing UTC components (the pre-fix behavior) would see day 12 vs 13
      // and miss "today" entirely; comparing local components correctly matches.
      expect(bucketKeyFor(photo, now)).toBe('today')
    } finally {
      process.env.TZ = originalTZ
    }
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
