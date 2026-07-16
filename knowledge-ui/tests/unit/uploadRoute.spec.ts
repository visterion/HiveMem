import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { vuetify } from '../../src/plugins/vuetify'
import { i18n } from '../../src/i18n'
import { router } from '../../src/router'
import UploadRoute from '../../src/pages/UploadRoute.vue'
import { useUploadsStore } from '../../src/stores/uploads'

const g = { global: { plugins: [vuetify, i18n, router] } }

describe('UploadRoute', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('shows the empty hint when there are no jobs', () => {
    const w = mount(UploadRoute, g)
    expect(w.get('[data-test="upload-empty"]').isVisible()).toBe(true)
  })

  it('renders a row per job with its status', async () => {
    const w = mount(UploadRoute, g)
    const s = useUploadsStore()
    s.jobs.push({ id: 'u1', file: new File(['a'], 'a.pdf'), fileName: 'a.pdf', size: 3, status: 'done', progress: 1, retryable: false, result: { cellId: 'c1', deduplicated: false } })
    await w.vm.$nextTick()
    const rows = w.findAll('[data-test="upload-job"]')
    expect(rows.length).toBe(1)
    expect(rows[0].text()).toContain('a.pdf')
  })

  it('shows a re-login banner when authError is set', async () => {
    const w = mount(UploadRoute, g)
    useUploadsStore().authError = true
    await w.vm.$nextTick()
    expect(w.find('[data-test="upload-relogin"]').exists()).toBe(true)
  })
})
