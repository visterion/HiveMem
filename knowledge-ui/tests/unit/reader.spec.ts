import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import Reader from '../../src/components/Reader.vue'
import { i18n } from '../../src/i18n'
import { useReaderStore } from '../../src/stores/reader'
import { useCellStore } from '../../src/stores/cell'

vi.mock('pdfjs-dist', () => ({
  GlobalWorkerOptions: { workerSrc: '' },
  getDocument: vi.fn(() => ({ promise: Promise.resolve({ numPages: 1, getPage: vi.fn() }) })),
}))

describe('Reader', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    i18n.global.locale.value = 'de'
    localStorage.setItem('hivemem_mock', 'true')
  })

  it('arms history-close when opened and closes on popstate', async () => {
    const push = vi.spyOn(history, 'pushState')
    const cell = useCellStore()
    cell.cache.set('c1', {
      cell: { id: 'c1', content: '# hi', attachments: [] } as any,
      facts: [], tunnels: [],
    })
    cell.currentId = 'c1'
    const reader = useReaderStore()
    reader.openReader('c1')

    mount(Reader, { global: { plugins: [i18n], stubs: { teleport: true } } })
    await flushPromises()
    expect(push).toHaveBeenCalled()

    window.dispatchEvent(new PopStateEvent('popstate'))
    await flushPromises()
    expect(reader.open).toBe(false)
  })

  it('pops the history sentinel when the reader is closed out-of-band', async () => {
    const back = vi.spyOn(history, 'back').mockImplementation(() => {})
    const cell = useCellStore()
    cell.cache.set('c1', {
      cell: { id: 'c1', content: '# hi', attachments: [] } as any,
      facts: [], tunnels: [],
    })
    cell.currentId = 'c1'
    const reader = useReaderStore()
    reader.openReader('c1')

    mount(Reader, { global: { plugins: [i18n], stubs: { teleport: true } } })
    await flushPromises()

    // Close NOT via requestClose (e.g. a route guard calling the store action).
    reader.close()
    await flushPromises()
    // disarm() consumed the pushed sentinel so it can't linger in the back-stack.
    expect(back).toHaveBeenCalledTimes(1)
  })
})
