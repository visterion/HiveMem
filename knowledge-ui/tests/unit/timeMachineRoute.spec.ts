import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TimeMachineRoute from '../../src/pages/TimeMachineRoute.vue'
import { resetApi } from '../../src/api/useApi'
import { i18n } from '../../src/i18n'
import { useCanvasStore } from '../../src/stores/canvas'
import type { Cell } from '../../src/api/types'

function makeCell(id: string, validFrom: string): Cell {
  return {
    id, realm: 'personal', signal: 'facts', topic: null, title: id,
    content: `content ${id}`, summary: `summary ${id}`, key_points: [], insight: null,
    tags: [], importance: 1, status: 'active', created_by: 'test',
    created_at: validFrom, valid_from: validFrom, valid_until: null
  } as Cell
}

const opts = { global: { plugins: [i18n] } }

async function mountReady() {
  const w = mount(TimeMachineRoute, opts)
  for (let i = 0; i < 80 && w.findAll('.tm-tick').length === 0; i++) {
    await new Promise(r => setTimeout(r, 25)); await flushPromises()
  }
  for (let i = 0; i < 10; i++) { await new Promise(r => setTimeout(r, 25)); await flushPromises() }
  return w
}

describe('TimeMachineRoute', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'de'
    setActivePinia(createPinia())
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
  })

  it('renders a slider and one tick per cell, present-focused by default', async () => {
    const w = await mountReady()
    const ticks = w.findAll('.tm-tick')
    expect(ticks.length).toBeGreaterThan(1)
    expect(w.find('.tm-range').exists()).toBe(true)
    expect(w.findAll('.tm-tick.on').length).toBe(ticks.length)
    expect(w.findAll('[data-test="tm-card"]').length).toBeGreaterThan(0)
  })

  it('dragging to oldest lights only the first tick and shows one card', async () => {
    const w = await mountReady()
    await w.find('.tm-range').setValue(0)
    await flushPromises()
    expect(w.findAll('.tm-tick.on').length).toBe(1)
    expect(w.findAll('[data-test="tm-card"]').length).toBe(1)
  })

  it('keeps tracking newest while cells stream in (until touched)', async () => {
    const w = await mountReady()
    const canvas = useCanvasStore()
    const before = canvas.cells.length
    canvas.cells = [
      ...canvas.cells,
      makeCell('stream-1', '2030-01-01'),
      makeCell('stream-2', '2030-01-02'),
      makeCell('stream-3', '2030-01-03'),
      makeCell('stream-4', '2030-01-04'),
      makeCell('stream-5', '2030-01-05'),
    ]
    await flushPromises()
    const range = w.find('.tm-range').element as HTMLInputElement
    expect(Number(range.value)).toBe(before + 5 - 1)
    // still untouched: another batch keeps tracking newest too
    canvas.cells = [...canvas.cells, makeCell('stream-6', '2030-01-06')]
    await flushPromises()
    expect(Number(range.value)).toBe(before + 6 - 1)
  })

  it('renders a list of up to 20 cells valid at the slider position, newest first', async () => {
    const w = await mountReady()
    const canvas = useCanvasStore()
    const extra = Array.from({ length: 30 }, (_, i) =>
      makeCell(`bulk-${i}`, `2031-01-${String((i % 28) + 1).padStart(2, '0')}`))
    canvas.cells = [...canvas.cells, ...extra]
    await flushPromises()
    const cards = w.findAll('[data-test="tm-card"]')
    expect(cards.length).toBe(20)
  })
})
