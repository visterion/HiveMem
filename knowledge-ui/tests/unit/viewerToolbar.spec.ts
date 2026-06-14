import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ViewerToolbar from '../../src/components/readers/ViewerToolbar.vue'
import { i18n } from '../../src/i18n'

function mountTb(props: Record<string, unknown>) {
  return mount(ViewerToolbar, { props, global: { plugins: [i18n] } })
}

describe('ViewerToolbar', () => {
  it('shows page controls and N/M only when pageCount > 1', () => {
    const single = mountTb({ page: 1, pageCount: 1, scalePct: 100 })
    expect(single.find('[data-test="vt-pages"]').exists()).toBe(false)
    const multi = mountTb({ page: 2, pageCount: 5, scalePct: 100 })
    expect(multi.find('[data-test="vt-pages"]').text()).toContain('2 / 5')
  })

  it('emits prev/next from page buttons', async () => {
    const w = mountTb({ page: 2, pageCount: 5, scalePct: 100 })
    await w.find('[data-test="vt-prev"]').trigger('click')
    await w.find('[data-test="vt-next"]').trigger('click')
    expect(w.emitted('prev')).toHaveLength(1)
    expect(w.emitted('next')).toHaveLength(1)
  })

  it('emits zoomIn/zoomOut and shows the scale percent', async () => {
    const w = mountTb({ page: 1, pageCount: 1, scalePct: 150 })
    expect(w.find('[data-test="vt-zoom"]').text()).toContain('150%')
    await w.find('[data-test="vt-zoom-in"]').trigger('click')
    await w.find('[data-test="vt-zoom-out"]').trigger('click')
    expect(w.emitted('zoomIn')).toHaveLength(1)
    expect(w.emitted('zoomOut')).toHaveLength(1)
  })

  it('emits download', async () => {
    const w = mountTb({ page: 1, pageCount: 1, scalePct: 100 })
    await w.find('[data-test="vt-download"]').trigger('click')
    expect(w.emitted('download')).toHaveLength(1)
  })
})
