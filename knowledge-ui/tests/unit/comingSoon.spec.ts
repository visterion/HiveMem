import { describe, expect, it } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { defineComponent, h } from 'vue'
import ComingSoon from '../../src/pages/ComingSoon.vue'
import { i18n } from '../../src/i18n'

describe('ComingSoon', () => {
  it('renders the title from route meta and the coming-soon line', async () => {
    i18n.global.locale.value = 'de'
    const router = createRouter({ history: createMemoryHistory(),
      routes: [{ path: '/', name: 'scans', component: ComingSoon, meta: { title: 'nav.scans' } }] })
    router.push('/'); await router.isReady()
    const w = mount(ComingSoon, { global: { plugins: [router, i18n] } })
    await flushPromises()
    expect(w.text()).toContain('Scans')
    expect(w.text()).toContain('In Arbeit')
  })
})
