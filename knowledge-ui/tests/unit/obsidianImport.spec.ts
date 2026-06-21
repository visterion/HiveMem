import { describe, it, expect } from 'vitest'
import { parseNote, buildPlan } from '../../src/composables/obsidianImport'

describe('parseNote', () => {
  it('derives the title from the filename (path + .md stripped)', () => {
    const n = parseNote('vault/sub/My Note.md', 'body')
    expect(n.title).toBe('My Note')
  })

  it('extracts frontmatter created → validFrom, realm/signal, and list-form tags', () => {
    const raw = `---
created: 2024-03-01
realm: research
signal: facts
tags:
  - alpha
  - beta
---
Body text here.`
    const n = parseNote('Note.md', raw)
    expect(n.validFrom).toBe('2024-03-01')
    expect(n.realm).toBe('research')
    expect(n.signal).toBe('facts')
    expect(n.tags).toEqual(expect.arrayContaining(['alpha', 'beta']))
    expect(n.content.trim()).toBe('Body text here.')
  })

  it('parses inline #tags from the body and merges with frontmatter tags (deduped)', () => {
    const raw = `---
tags: [alpha]
---
Some text #alpha #project/sub and #done`
    const n = parseNote('N.md', raw)
    expect(n.tags).toContain('alpha')
    expect(n.tags).toContain('project/sub')
    expect(n.tags).toContain('done')
    expect(n.tags.filter(t => t === 'alpha')).toHaveLength(1)
  })

  it('extracts wiki-links and strips aliases', () => {
    const n = parseNote('N.md', 'See [[Target A]] and [[Target B|the alias]].')
    expect(n.links).toEqual(['Target A', 'Target B'])
  })

  it('handles a note with no frontmatter', () => {
    const n = parseNote('N.md', 'plain body, no fm')
    expect(n.realm).toBeUndefined()
    expect(n.validFrom).toBeUndefined()
    expect(n.content).toBe('plain body, no fm')
    expect(n.tags).toEqual([])
    expect(n.links).toEqual([])
  })
})

describe('buildPlan', () => {
  it('counts notes, unique tags, links and flags missing link targets', () => {
    const files = [
      { name: 'A.md', text: 'links to [[B]] and [[Ghost]] #x' },
      { name: 'B.md', text: 'just text #x #y' },
    ]
    const plan = buildPlan(files)
    expect(plan.notes).toHaveLength(2)
    expect(plan.stats.noteCount).toBe(2)
    expect(plan.stats.tagCount).toBe(2) // x, y
    expect(plan.stats.linkCount).toBe(2) // B, Ghost
    // "Ghost" is not a note title → flagged as a stub target to be created
    expect(plan.missingTargets).toContain('Ghost')
    expect(plan.missingTargets).not.toContain('B')
  })
})
