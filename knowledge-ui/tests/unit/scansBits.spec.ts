import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import DocThumb from '../../src/components/scans/DocThumb.vue'
import Snippet from '../../src/components/scans/Snippet.vue'
import FacetGroup from '../../src/components/scans/FacetGroup.vue'

describe('scans bits', () => {
  it('DocThumb shows an img when has_thumbnail, placeholder otherwise', () => {
    const withThumb: any = { id:'1', attachment_id:'att-1', has_thumbnail:true, page_count:3, status:'committed', tags:[] }
    const w = mount(DocThumb, { props: { d: withThumb } })
    expect(w.find('img').exists()).toBe(true)
    expect(w.find('img').attributes('src')).toContain('/api/attachments/att-1/thumbnail')
    const noThumb: any = { id:'2', has_thumbnail:false, status:'pending', tags:[] }
    const w2 = mount(DocThumb, { props: { d: noThumb } })
    expect(w2.find('img').exists()).toBe(false)
    expect(w2.find('.dt-paper').exists()).toBe(true)
  })
  it('Snippet highlights the query', () => {
    const w = mount(Snippet, { props: { text: 'foo [page=1] hello WORLD bar baz', q: 'world' } })
    expect(w.find('mark').exists()).toBe(true)
    expect(w.find('mark').text().toLowerCase()).toBe('world')
  })
  it('Snippet renders nothing when query absent or not found', () => {
    expect(mount(Snippet, { props: { text: 'abc', q: '' } }).find('.ocr-snip').exists()).toBe(false)
    expect(mount(Snippet, { props: { text: 'abc', q: 'zzz' } }).find('.ocr-snip').exists()).toBe(false)
  })
  it('FacetGroup renders rows with counts and emits toggle', async () => {
    const w = mount(FacetGroup, { props: {
      title: 'Tags', field: 'tag',
      options: [{value:'contract',count:5},{value:'invoice',count:2}],
      selected: new Set<string>(),
    }})
    expect(w.findAll('.facet-row').length).toBe(2)
    expect(w.text()).toContain('contract'); expect(w.text()).toContain('5')
    await w.findAll('.facet-row')[0].trigger('click')
    expect(w.emitted('toggle')![0]).toEqual(['tag','contract'])
  })
})
