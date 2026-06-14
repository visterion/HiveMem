import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import DocCard from '../../src/components/scans/DocCard.vue'
import DocTable from '../../src/components/scans/DocTable.vue'
import { i18n } from '../../src/i18n'

const row: any = { id:'d1', realm:'documents', signal:'facts', topic:null, summary:'Mietvertrag 2025',
  tags:['contract'], importance:2, status:'committed', created_at:'2025-03-01T00:00:00Z',
  attachment_id:'att-d1', mime_type:'application/pdf', page_count:3, has_thumbnail:true }

describe('DocCard + DocTable', () => {
  it('DocCard renders title/date/thumb; emits open on body click and select on checkbox', async () => {
    const w = mount(DocCard, { props: { d: row, q: '', selected: false }, global: { plugins: [i18n] } })
    expect(w.text()).toContain('Mietvertrag 2025')
    expect(w.find('.docthumb').exists()).toBe(true)
    await w.find('.dc-thumb').trigger('click')
    expect(w.emitted('open')).toBeTruthy()        // thumbnail → document viewer
    await w.find('.dc-body').trigger('click')
    expect(w.emitted('openInfo')).toBeTruthy()    // text → overview (summaries + raw text)
    await w.find('.dc-check').trigger('click')
    expect(w.emitted('select')).toBeTruthy()
  })

  it('DocCard title prefers the LLM short title (topic) over the summary', () => {
    const titled = { ...row, topic: 'Schornsteinfeger-Rechnung 2025' }
    const w = mount(DocCard, { props: { d: titled, q: '', selected: false }, global: { plugins: [i18n] } })
    expect(w.find('.dc-title').text()).toBe('Schornsteinfeger-Rechnung 2025')
  })
  it('DocTable renders a row and emits open on title click', async () => {
    const w = mount(DocTable, { props: { rows: [row], q: '', selection: new Set<string>() }, global: { plugins: [i18n] } })
    expect(w.text()).toContain('Mietvertrag 2025')
    await w.find('.dtbl-title').trigger('click')
    expect(w.emitted('open')![0]).toEqual(['d1'])
  })
})
