import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import Snippet from '../../src/components/scans/Snippet.vue'

describe('Snippet', () => {
  it('highlights a literal match of the query', () => {
    const w = mount(Snippet, { props: { text: 'Hallo Welt, wie geht es dir?', q: 'Welt' } })
    expect(w.find('mark').text()).toBe('Welt')
  })

  it('falls back to summary head when query has no literal match', () => {
    const w = mount(Snippet, { props: { text: 'Zusammenfassung ohne Treffer-Wort.', q: 'xyz' } })
    expect(w.text()).toContain('Zusammenfassung ohne')
  })

  it('still strips [page=N] markers', () => {
    const w = mount(Snippet, { props: { text: '[page=1] Hallo Welt', q: 'Hallo' } })
    expect(w.text()).not.toContain('[page=')
  })

  it('renders nothing when query is empty', () => {
    const w = mount(Snippet, { props: { text: 'Some text', q: '' } })
    expect(w.find('.ocr-snip').exists()).toBe(false)
  })

  it('renders nothing when text is empty even with a query', () => {
    const w = mount(Snippet, { props: { text: '', q: 'xyz' } })
    expect(w.find('.ocr-snip').exists()).toBe(false)
  })
})
