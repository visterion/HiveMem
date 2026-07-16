import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

vi.mock('../../src/api/uploadClient', async () => {
  const actual = await vi.importActual<any>('../../src/api/uploadClient')
  return { ...actual, uploadAttachment: vi.fn(() => Promise.resolve({ cellId: 'c1', deduplicated: false })) }
})

import { createPinia, setActivePinia } from 'pinia'
import { vuetify } from '../../src/plugins/vuetify'
import { i18n } from '../../src/i18n'
import { router } from '../../src/router'
import UploadFab from '../../src/components/shell/UploadFab.vue'
import { useUploadsStore } from '../../src/stores/uploads'

describe('UploadFab', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('has a file input and a camera-capture input with correct attrs', () => {
    const w = mount(UploadFab, { global: { plugins: [vuetify, i18n, router] } })
    const file = w.get('[data-test="upload-fab-file"]')
    expect(file.attributes('accept')).toContain('image/')
    expect(file.attributes('accept')).toContain('application/pdf')
    expect(file.attributes('multiple')).toBeDefined()
    const cam = w.get('[data-test="upload-fab-camera"]')
    expect(cam.attributes('capture')).toBe('environment')
  })

  it('enqueues selected files into the store', async () => {
    const w = mount(UploadFab, { global: { plugins: [vuetify, i18n, router] } })
    const store = useUploadsStore()
    const input = w.get('[data-test="upload-fab-file"]').element as HTMLInputElement
    const dt = new DataTransfer()
    dt.items.add(new File(['a'], 'a.pdf', { type: 'application/pdf' }))
    Object.defineProperty(input, 'files', { value: dt.files })
    await w.get('[data-test="upload-fab-file"]').trigger('change')
    expect(store.jobs.length).toBe(1)
    expect(store.jobs[0].fileName).toBe('a.pdf')
  })
})
