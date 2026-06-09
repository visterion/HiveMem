import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import HmIcon from '../../src/components/shell/HmIcon.vue'

describe('HmIcon', () => {
  it('renders an svg for a known name', () => {
    const w = mount(HmIcon, { props: { name: 'search' } })
    expect(w.find('svg').exists()).toBe(true)
    expect(w.find('svg').attributes('width')).toBe('22')
  })
  it('respects the size prop', () => {
    const w = mount(HmIcon, { props: { name: 'queen', size: 18 } })
    expect(w.find('svg').attributes('width')).toBe('18')
  })
  it('renders nothing meaningful for an unknown name but does not throw', () => {
    const w = mount(HmIcon, { props: { name: 'nope' } })
    expect(w.find('svg').exists()).toBe(true)
  })
})
