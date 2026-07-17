import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import SortMenu from '../../src/components/scans/SortMenu.vue'
import { i18n } from '../../src/i18n'

function mountMenu(props: Record<string, unknown>) {
  return mount(SortMenu, {
    props: { sort: 'newest', options: [['newest', 'Newest'], ['oldest', 'Oldest']], ...props },
    global: { plugins: [i18n] },
  })
}

describe('SortMenu popup alignment', () => {
  it('defaults to right alignment so the scans toolbar keeps its current behaviour', async () => {
    const w = mountMenu({})
    await w.find('.sort-btn').trigger('click')
    expect(w.find('.sort-pop').classes()).toContain('align-right')
    expect(w.find('.sort-pop').classes()).not.toContain('align-left')
  })

  it('anchors left when align="left"', async () => {
    const w = mountMenu({ align: 'left' })
    await w.find('.sort-btn').trigger('click')
    expect(w.find('.sort-pop').classes()).toContain('align-left')
  })
})
