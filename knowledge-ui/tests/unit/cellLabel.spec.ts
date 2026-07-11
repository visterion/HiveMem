import { describe, it, expect } from 'vitest'
import { cellLabel, contentSnippet } from '../../src/api/cellLabel'

describe('cellLabel', () => {
  it('prefers summary when present', () => {
    expect(cellLabel({ id: 'abc12345', summary: 'A short summary', content: 'x' })).toBe('A short summary')
  })

  it('falls back to a content snippet, stripping [page=N] markers', () => {
    const c = { id: 'abc12345', summary: null, content: '[page=1]\nHUK-COBURG VVaG\nPostanschrift' }
    expect(cellLabel(c)).toBe('HUK-COBURG VVaG Postanschrift')
  })

  it('falls back to topic, then short id', () => {
    expect(cellLabel({ id: 'deadbeef-1', summary: null, content: '', topic: 'invoices' })).toBe('invoices')
    expect(cellLabel({ id: 'deadbeef-1', summary: null, content: null, topic: null })).toBe('#deadbeef')
  })

  it('truncates very long summaries', () => {
    const long = 'x'.repeat(200)
    expect(cellLabel({ id: 'a', summary: long }).endsWith('…')).toBe(true)
  })

  it('contentSnippet collapses whitespace and caps length', () => {
    expect(contentSnippet('  a\n\n  b   c ')).toBe('a b c')
    expect(contentSnippet('y'.repeat(200)).length).toBe(80)
  })

  it('content-snippet labels end with an ellipsis when truncated', () => {
    expect(cellLabel({ id: 'a', content: 'x'.repeat(200) } as any)).toMatch(/…$/)
  })
})
