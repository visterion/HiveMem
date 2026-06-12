import { describe, it, expect } from 'vitest'
import { sortByValidFrom } from '../../src/composables/timeline'

const cell = (id: string, valid_from: string) => ({
  id, realm: 'r', signal: null, topic: null, title: id, content: '', summary: null,
  key_points: [], insight: null, tags: [], importance: 1, status: 'committed',
  created_by: 'x', created_at: valid_from, valid_from, valid_until: null,
}) as any

describe('sortByValidFrom', () => {
  it('sorts ascending by valid_from', () => {
    const out = sortByValidFrom([cell('c', '2026-03-01'), cell('a', '2026-01-01'), cell('b', '2026-02-01')])
    expect(out.map(c => c.id)).toEqual(['a', 'b', 'c'])
  })
  it('does not mutate the input array', () => {
    const input = [cell('c', '2026-03-01'), cell('a', '2026-01-01')]
    const copy = [...input]
    sortByValidFrom(input)
    expect(input.map(c => c.id)).toEqual(copy.map(c => c.id))
  })
  it('keeps relative order for equal/empty valid_from (stable)', () => {
    const out = sortByValidFrom([cell('x', ''), cell('y', ''), cell('z', '2026-01-01')])
    expect(out.map(c => c.id)).toEqual(['x', 'y', 'z'])
  })
})
