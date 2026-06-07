import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import DocDetail from '../../src/components/scans/DocDetail.vue'
import { i18n } from '../../src/i18n'
import { resetApi } from '../../src/api/useApi'
import { useReaderStore } from '../../src/stores/reader'

const row: any = { id:'d1', realm:'documents', signal:'facts', topic:null, summary:'Mietvertrag 2025',
  tags:['contract','ocr_pending'], importance:2, status:'pending', created_at:'2025-03-01T00:00:00Z',
  attachment_id:'att-d1', mime_type:'application/pdf', page_count:3, has_thumbnail:true }

describe('DocDetail', () => {
  beforeEach(() => { setActivePinia(createPinia()); i18n.global.locale.value='de'
    localStorage.setItem('hivemem_mock','true'); resetApi(); vi.useFakeTimers() })
  afterEach(() => vi.useRealTimers())

  it('renders metadata + a state pill from tags; open-original calls the reader', async () => {
    const w = mount(DocDetail, { props: { d: row, q: '' }, global: { plugins: [i18n] } })
    await vi.advanceTimersByTimeAsync(400); await flushPromises()
    expect(w.text()).toContain('Mietvertrag 2025')         // title
    expect(w.find('.states').exists()).toBe(true)           // state pipeline rendered
    expect(w.findAll('.state-pill').length).toBeGreaterThan(0)
    // open original
    const reader = useReaderStore()
    await w.find('[data-test="open-original"]').trigger('click')
    expect(reader.cellId === 'd1' || reader.open === true).toBe(true)
  })
})
