import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import KnowledgeReader from '../../src/components/knowledge/KnowledgeReader.vue'
import { i18n } from '../../src/i18n'
import { useCellStore } from '../../src/stores/cell'

function seed() {
  const s = useCellStore()
  const cell: any = { id: 'c1', realm: 'docs', signal: 'facts', topic: null, title: 'Mietvertrag',
    content: 'VOLLTEXT-INHALT', summary: 'Eine Zusammenfassung', key_points: ['Punkt eins', 'Punkt zwei'],
    insight: 'Der Kern-Insight', tags: [], importance: 2, status: 'committed', created_by: 'u',
    created_at: '', valid_from: '2024-01-01', valid_until: null }
  s.store('c1', { cell, facts: [], tunnels: [] })
  s.currentId = 'c1'
  return s
}

describe('KnowledgeReader', () => {
  beforeEach(() => { setActivePinia(createPinia()); i18n.global.locale.value = 'de' })

  it('shows the empty state when no cell is selected', () => {
    const w = mount(KnowledgeReader, { global: { plugins: [i18n] } })
    expect(w.text()).toContain('Zelle wählen')
  })

  it('renders the title and switches between the 4 layers', async () => {
    seed()
    const w = mount(KnowledgeReader, { global: { plugins: [i18n] } })
    // Header uses cellLabel() (summary first), NOT the unreliable title field
    expect(w.find('.title').text()).toContain('Eine Zusammenfassung')
    expect(w.text()).toContain('Eine Zusammenfassung') // summary tab default
    const tabs = w.findAll('.tab')
    expect(tabs.length).toBe(4)
    // key points tab
    await tabs[1].trigger('click')
    expect(w.text()).toContain('Punkt eins')
    // insight tab
    await tabs[2].trigger('click')
    expect(w.text()).toContain('Der Kern-Insight')
    // full text tab
    await tabs[3].trigger('click')
    expect(w.text()).toContain('VOLLTEXT-INHALT')
  })
})
