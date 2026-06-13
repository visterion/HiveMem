import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import DocDetail from '../../src/components/scans/DocDetail.vue'
import { i18n } from '../../src/i18n'
import { resetApi } from '../../src/api/useApi'
import { useReaderStore } from '../../src/stores/reader'
import { useScansStore } from '../../src/stores/scans'
import { useCellStore } from '../../src/stores/cell'
import { useApi } from '../../src/api/useApi'

const row: any = { id:'d1', realm:'documents', signal:'facts', topic:null, summary:'Mietvertrag 2025',
  tags:['contract','ocr_pending'], importance:2, status:'pending', created_at:'2025-03-01T00:00:00Z',
  attachment_id:'att-d1', mime_type:'application/pdf', page_count:3, has_thumbnail:true,
  confidence: 0.75, correspondent: 'Finanzamt Berlin' }

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
    await vi.advanceTimersByTimeAsync(400); await flushPromises()
    expect(reader.cellId === 'd1' || reader.open === true).toBe(true)
  })

  it('open-original loads the scan into the cellStore so the reader renders the right cell', async () => {
    // The reader (Reader.vue) renders entirely from cellStore.current — never from
    // reader.cellId. So opening the reader without seeding the cellStore shows a blank
    // (current === null) or a stale, unrelated cell. open-original must load THIS scan.
    const fullCell: any = {
      id: 'd1', realm: 'documents', signal: 'facts', topic: null, title: '',
      content: 'OCR body of the scan', summary: 'Mietvertrag 2025', key_points: [],
      insight: null, tags: ['contract'], importance: 2, status: 'pending',
      created_by: 'admin', created_at: '2025-03-01T00:00:00Z', valid_from: '2025-03-01T00:00:00Z',
      valid_until: null,
      attachments: [{ id: 'att-d1', mime_type: 'application/pdf', original_filename: 'scan.pdf', size_bytes: 1 }],
    }
    const api = useApi()
    vi.spyOn(api, 'call').mockImplementation(async (tool: string) => {
      if (tool === 'get_cell') return fullCell as any
      if (tool === 'traverse') return [] as any
      if (tool === 'quick_facts') return [] as any
      if (tool === 'search') return [] as any
      return undefined as any
    })

    const w = mount(DocDetail, { props: { d: row, q: '' }, global: { plugins: [i18n] } })
    await vi.advanceTimersByTimeAsync(400); await flushPromises()

    const cell = useCellStore()
    const reader = useReaderStore()
    await w.find('[data-test="open-original"]').trigger('click')
    await flushPromises()

    expect(reader.open).toBe(true)
    expect(cell.currentId).toBe('d1')
    expect(cell.current?.cell.content).toBe('OCR body of the scan')
    expect(cell.current?.cell.attachments?.length).toBe(1)
  })

  it('renders confidence bar when d.confidence is set', async () => {
    const w = mount(DocDetail, { props: { d: row, q: '' }, global: { plugins: [i18n] } })
    await vi.advanceTimersByTimeAsync(400); await flushPromises()
    expect(w.find('[data-test="confidence-bar"]').exists()).toBe(true)
    expect(w.text()).toContain('75%')
  })

  it('does not render confidence bar when d.confidence is null', async () => {
    const noConf = { ...row, confidence: null }
    const w = mount(DocDetail, { props: { d: noConf, q: '' }, global: { plugins: [i18n] } })
    await vi.advanceTimersByTimeAsync(400); await flushPromises()
    expect(w.find('[data-test="confidence-bar"]').exists()).toBe(false)
  })

  it('shows correspondent from d.correspondent in meta grid', async () => {
    const w = mount(DocDetail, { props: { d: row, q: '' }, global: { plugins: [i18n] } })
    await vi.advanceTimersByTimeAsync(400); await flushPromises()
    expect(w.text()).toContain('Finanzamt Berlin')
  })

  it('addTag calls scans.editTags and updates cellTags', async () => {
    const w = mount(DocDetail, { props: { d: row, q: '' }, global: { plugins: [i18n] } })
    await vi.advanceTimersByTimeAsync(400); await flushPromises()
    const s = useScansStore()
    const editSpy = vi.spyOn(s, 'editTags').mockResolvedValue()
    vi.stubGlobal('prompt', () => 'newtag')
    await w.find('.dm-tag-add').trigger('click')
    await flushPromises()
    expect(editSpy).toHaveBeenCalledWith('d1', ['newtag'], [])
    vi.unstubAllGlobals()
  })

  it('removeTag (×) calls scans.editTags with remove and updates cellTags', async () => {
    const w = mount(DocDetail, { props: { d: row, q: '' }, global: { plugins: [i18n] } })
    await vi.advanceTimersByTimeAsync(400); await flushPromises()
    const s = useScansStore()
    const editSpy = vi.spyOn(s, 'editTags').mockResolvedValue()
    // click the × on the first tag
    await w.findAll('.dm-tag-del')[0].trigger('click')
    await flushPromises()
    expect(editSpy).toHaveBeenCalledWith('d1', [], expect.arrayContaining([expect.any(String)]))
    vi.unstubAllGlobals()
  })
})
