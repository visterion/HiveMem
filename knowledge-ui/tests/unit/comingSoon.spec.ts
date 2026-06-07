import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ComingSoon from '../../src/pages/ComingSoon.vue'
import { i18n } from '../../src/i18n'

describe('ComingSoon', () => {
  it('renders the title from route meta and the coming-soon line', () => {
    i18n.global.locale.value = 'de'
    const w = mount(ComingSoon, {
      global: {
        plugins: [i18n],
        mocks: { $route: { meta: { title: 'nav.scans' } } },
      },
    })
    expect(w.text()).toContain('Scans')
    expect(w.text()).toContain('In Arbeit')
  })
})
