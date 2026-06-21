import { describe, it, expect } from 'vitest'
import { cellToMarkdown, cellMarkdownFilename } from '../../src/composables/cellMarkdown'
import type { Cell } from '../../src/api/types'

function makeCell(over: Partial<Cell> = {}): Cell {
  return {
    id: 'c1', realm: 'engineering', signal: 'facts', topic: 'raft', title: '',
    content: '# Raft\n\nConsensus.', summary: 'Raft summary', key_points: ['a', 'b'],
    insight: 'simple', tags: ['distributed', 'consensus'], importance: 3, status: 'committed',
    created_by: 'me', created_at: '2026-01-01T00:00:00Z', valid_from: '2026-01-02T00:00:00Z',
    valid_until: null, ...over,
  }
}

describe('cellToMarkdown', () => {
  it('emits YAML frontmatter with realm/signal/topic/tags/valid_from then the body', () => {
    const md = cellToMarkdown(makeCell())
    expect(md.startsWith('---\n')).toBe(true)
    expect(md).toContain('realm: engineering')
    expect(md).toContain('signal: facts')
    expect(md).toContain('topic: raft')
    expect(md).toContain('valid_from: 2026-01-02T00:00:00Z')
    expect(md).toMatch(/tags:\n {2}- distributed\n {2}- consensus/)
    // body follows the closing fence
    expect(md).toContain('---\n\n# Raft\n\nConsensus.')
  })

  it('omits null/empty fields from the frontmatter', () => {
    const md = cellToMarkdown(makeCell({ signal: null, topic: null, tags: [] }))
    expect(md).not.toContain('signal:')
    expect(md).not.toContain('topic:')
    expect(md).not.toContain('tags:')
  })

  it('derives a filesystem-safe .md filename from topic/summary', () => {
    expect(cellMarkdownFilename(makeCell({ topic: 'My Topic!' }))).toBe('My-Topic.md')
    expect(cellMarkdownFilename(makeCell({ topic: null, summary: 'Some Summary' }))).toBe('Some-Summary.md')
    expect(cellMarkdownFilename(makeCell({ topic: null, summary: null }))).toBe('c1.md')
  })
})
