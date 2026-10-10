import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import MarkdownTab from '../../src/components/readers/MarkdownTab.vue'

describe('MarkdownTab KaTeX rendering (H7)', () => {
  it('renders plain-text dollar amounts literally, without katex spans or escaped markup', () => {
    const w = mount(MarkdownTab, { props: { content: 'Costs are $50 and $60 today' } })
    expect(w.html()).not.toContain('katex')
    expect(w.text()).toContain('Costs are $50 and $60 today')
    // Must not have been HTML-escaped by a source-injection pass
    expect(w.html()).not.toMatch(/&lt;span class=.*katex/)
  })

  it('renders inline math delimited by single $ as a KaTeX element', () => {
    const w = mount(MarkdownTab, { props: { content: 'Formula: $x^2$ is nice.' } })
    expect(w.find('.katex').exists()).toBe(true)
  })

  it('leaves a dollar sign inside an inline code span untouched', () => {
    const w = mount(MarkdownTab, { props: { content: '`echo $HOME`' } })
    const code = w.find('code')
    expect(code.exists()).toBe(true)
    expect(code.text()).toBe('echo $HOME')
    expect(w.find('.katex').exists()).toBe(false)
  })

  it('renders block math delimited by $$ as a KaTeX display element', () => {
    const w = mount(MarkdownTab, { props: { content: '$$\\sqrt{3x-1}$$' } })
    expect(w.find('.katex-display').exists()).toBe(true)
  })

  it('renders with the same KaTeX version whose stylesheet is imported', () => {
    // MarkdownTab imports katex/dist/katex.min.css from the top-level katex
    // package, while @vscode/markdown-it-katex renders with whatever katex it
    // resolves. KaTeX 0.18 prefixed its internal classes (base -> katex-base,
    // strut -> katex-strut), so a renderer older than the stylesheet emits
    // markup the CSS no longer styles. Guard that both resolve to 0.18+.
    const w = mount(MarkdownTab, { props: { content: 'Formula: $\\frac{a}{b}$' } })
    expect(w.find('.katex .katex-base').exists()).toBe(true)
    expect(w.find('.katex .katex-strut').exists()).toBe(true)
    expect(w.find('.katex .base').exists()).toBe(false)
  })
})
