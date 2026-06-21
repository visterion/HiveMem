import type { Cell } from '../api/types'

// Serialize a cell to a Markdown document with YAML frontmatter (realm/signal/topic/
// tags/valid_from + importance), followed by the cell body. Round-trips with the
// Obsidian-style import (frontmatter + wiki-links/#tags in the body).
export function cellToMarkdown(cell: Cell): string {
  const fm: string[] = ['---', `realm: ${cell.realm}`]
  if (cell.signal) fm.push(`signal: ${cell.signal}`)
  if (cell.topic) fm.push(`topic: ${cell.topic}`)
  if (cell.importance) fm.push(`importance: ${cell.importance}`)
  if (cell.valid_from) fm.push(`valid_from: ${cell.valid_from}`)
  if (cell.tags && cell.tags.length) {
    fm.push('tags:')
    for (const tag of cell.tags) fm.push(`  - ${tag}`)
  }
  fm.push('---')
  return `${fm.join('\n')}\n\n${cell.content ?? ''}`
}

// A filesystem-safe .md filename derived from topic, then summary, then id.
export function cellMarkdownFilename(cell: Cell): string {
  const base = (cell.topic?.trim() || cell.summary?.trim() || cell.id)
    .replace(/[^\w\s-]/g, '')   // drop punctuation
    .trim()
    .replace(/\s+/g, '-')       // spaces → hyphens
    .slice(0, 80) || cell.id
  return `${base}.md`
}
