import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import DocInfoTab from '../../src/components/readers/DocInfoTab.vue'
import { i18n } from '../../src/i18n'
import { useCellStore } from '../../src/stores/cell'
import { useScansStore } from '../../src/stores/scans'

const cell: any = {
  id: 'd1', realm: 'documents', signal: 'facts', topic: 'rent', title: '',
  content: 'RAW OCR LINE ONE\nRAW OCR LINE TWO',
  summary: 'Mietvertrag 2025 zwischen A und B.',
  key_points: ['Kaltmiete 800 EUR', 'Kaution 2400 EUR'],
  insight: 'Befristet auf 2 Jahre.',
  tags: ['contract'], importance: 2, status: 'committed',
  created_at: '2025-03-01T00:00:00Z', attachments: [],
}

const row: any = {
  id: 'd1', realm: 'documents', summary: cell.summary, tags: ['contract'],
  status: 'pending', created_at: '2025-03-01T00:00:00Z', attachment_id: 'att-d1',
  mime_type: 'application/pdf', page_count: 3, has_thumbnail: true,
  confidence: 0.75, correspondent: 'Finanzamt Berlin',
}

function seed() {
  const cells = useCellStore()
  cells.cache.set('d1', { cell, facts: [], tunnels: [] })
  cells.currentId = 'd1'
  const scans = useScansStore()
  scans.results = [row]
}

function mountTab() {
  return mount(DocInfoTab, { global: { plugins: [i18n], stubs: { teleport: true } } })
}

describe('DocInfoTab', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    i18n.global.locale.value = 'de'
    localStorage.setItem('hivemem_mock', 'true')
  })

  it('renders all four layers: summary, key points, insight and raw text', () => {
    seed()
    const w = mountTab()
    expect(w.find('[data-test="di-summary"]').text()).toContain('Mietvertrag 2025')
    const kp = w.find('[data-test="di-keypoints"]').text()
    expect(kp).toContain('Kaltmiete 800 EUR')
    expect(kp).toContain('Kaution 2400 EUR')
    expect(w.find('[data-test="di-insight"]').text()).toContain('Befristet auf 2 Jahre')
    expect(w.find('[data-test="di-text"]').text()).toContain('RAW OCR LINE ONE')
  })

  it('shows scan metadata (correspondent, confidence, pages) from the matching document row', () => {
    seed()
    const w = mountTab()
    expect(w.text()).toContain('Finanzamt Berlin')
    expect(w.find('[data-test="di-confidence"]').text()).toContain('75%')
    expect(w.text()).toContain('3') // page count
  })

  it('removing a tag calls editTags with the tag in the remove list', async () => {
    seed()
    const scans = useScansStore()
    const spy = vi.spyOn(scans, 'editTags').mockResolvedValue(undefined as any)
    const w = mountTab()
    await w.find('[data-test="di-tag-del"]').trigger('click')
    expect(spy).toHaveBeenCalledWith('d1', [], ['contract'])
  })

  it('shows an approve button for a pending scan and calls approve_pending', async () => {
    seed()
    const w = mountTab()
    const btn = w.find('[data-test="di-approve"]')
    expect(btn.exists()).toBe(true)
    await btn.trigger('click')
    await flushPromises()
    // status flips locally so the button disappears after approval
    expect(w.find('[data-test="di-approve"]').exists()).toBe(false)
  })

  it('omits the scan metadata block when no matching document row exists (e.g. a knowledge cell)', () => {
    const cells = useCellStore()
    cells.cache.set('k1', {
      cell: { ...cell, id: 'k1', realm: 'engineering', status: 'committed' },
      facts: [], tunnels: [],
    })
    cells.currentId = 'k1'
    useScansStore().results = []
    const w = mountTab()
    expect(w.find('[data-test="di-meta"]').exists()).toBe(false)
    // layers still render
    expect(w.find('[data-test="di-summary"]').exists()).toBe(true)
  })
})
