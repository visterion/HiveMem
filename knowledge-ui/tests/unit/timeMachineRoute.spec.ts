import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TimeMachineRoute from '../../src/pages/TimeMachineRoute.vue'
import { resetApi } from '../../src/api/useApi'
import { i18n } from '../../src/i18n'

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
    expect(w.find('.tm-card').exists()).toBe(true)
  })

  it('dragging to oldest lights only the first tick', async () => {
    const w = await mountReady()
    await w.find('.tm-range').setValue(0)
    await flushPromises()
    expect(w.findAll('.tm-tick.on').length).toBe(1)
  })
})
