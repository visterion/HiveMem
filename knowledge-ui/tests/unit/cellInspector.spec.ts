import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { defineComponent, h } from 'vue'
import CellInspector from '../../src/components/knowledge/CellInspector.vue'
import { i18n } from '../../src/i18n'
import { useCellStore } from '../../src/stores/cell'

const Blank = defineComponent({ render: () => h('div') })
const router = createRouter({ history: createMemoryHistory(),
  routes: [{ path: '/', name: 'search', component: Blank }, { path: '/graph', name: 'graph', component: Blank }] })

function seed() {
  const s = useCellStore()
  const cell: any = { id: 'c1', realm: 'docs', signal: 'facts', topic: null, title: '', content: 'x',
    summary: 'Zusammenfassung', key_points: [], insight: null, tags: [], importance: 2, status: 'committed',
    created_by: 'u', created_at: '', valid_from: '2024-01-01', valid_until: null }
  s.store('c1', { cell, facts: [], tunnels: [] })
  s.currentId = 'c1'
  s.selectedScores = { ...cell, score_total: 0.7, score_semantic: 0.8, score_keyword: 0.4, score_recency: 0.3, score_importance: 0.6, score_popularity: 0.2, score_graph_proximity: 0.5 }
  return s
}

describe('CellInspector', () => {
  beforeEach(() => { setActivePinia(createPinia()); i18n.global.locale.value = 'de' })

  it('renders nothing when no cell is selected', () => {
    const w = mount(CellInspector, { global: { plugins: [router, i18n] } })
    expect(w.find('.inspector').exists()).toBe(false)
  })

  it('renders 6 signal bars and clears the store on close', async () => {
    const s = seed()
    const w = mount(CellInspector, { global: { plugins: [router, i18n] } })
    expect(w.findAll('.sig').length).toBe(6)
    await w.find('[data-test="insp-close"]').trigger('click')
    expect(s.currentId).toBeNull()
  })
})
