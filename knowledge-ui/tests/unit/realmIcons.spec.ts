import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import HmIcon from '../../src/components/shell/HmIcon.vue'

describe('HmIcon lock/cloud', () => {
  it('renders the lock icon inner markup', () => {
    const w = mount(HmIcon, { props: { name: 'lock' } })
    const html = w.html()
    expect(html).toContain('x="5"')
    expect(html).toContain('y="11"')
  })

  it('renders the cloud icon inner markup', () => {
    const w = mount(HmIcon, { props: { name: 'cloud' } })
    expect(w.html()).toContain('M7 18a4 4 0 0 1 0-8')
  })
})
