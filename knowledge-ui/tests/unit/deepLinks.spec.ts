import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import Reader from '../../src/components/Reader.vue'
import ScansResults from '../../src/components/scans/ScansResults.vue'
import KnowledgeReader from '../../src/components/knowledge/KnowledgeReader.vue'
import { i18n } from '../../src/i18n'
import { resetApi } from '../../src/api/useApi'
import { useScansStore } from '../../src/stores/scans'
import { useReaderStore } from '../../src/stores/reader'
import { useCellStore } from '../../src/stores/cell'

vi.mock('pdfjs-dist', () => ({
  GlobalWorkerOptions: { workerSrc: '' },
  getDocument: vi.fn(() => ({ promise: Promise.resolve({ numPages: 1, getPage: vi.fn() }) })),
}))

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'search', component: { template: '<div/>' } },
      { path: '/scans', name: 'scans', component: { template: '<div/>' } },
    ],
  })
}

async function settleHistory() {
  // pushState/back() in happy-dom need a couple of microtask flushes before
  // location.href reflects the new entry.
  await flushPromises()
  await new Promise(r => setTimeout(r, 0))
  await new Promise(r => setTimeout(r, 0))
  await flushPromises()
}

describe('deep links', () => {
  let vuetify: ReturnType<typeof createVuetify>

  beforeEach(() => {
    setActivePinia(createPinia())
    i18n.global.locale.value = 'de'
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
    vuetify = createVuetify({ components, directives })
  })
  afterEach(() => vi.useRealTimers())

  describe('scans reader (?doc=)', () => {
    it('opening a document writes ?doc= into the URL and closing removes it', async () => {
      const router = makeRouter()
      await router.push('/scans')
      await router.isReady()

      const scans = useScansStore()
      await scans.load()
      const id = scans.results[0].id

      mount(Reader, { global: { plugins: [i18n, router], stubs: { teleport: true } } })
      await flushPromises()

      await scans.openDocument(id, 'info')
      await settleHistory()

      expect(new URL(location.href).searchParams.get('doc')).toBe(id)

      useReaderStore().close()
      await settleHistory()

      expect(new URL(location.href).searchParams.get('doc')).toBeNull()
    })

    it('mounting /scans?doc=<id> opens the reader', async () => {
      const router = makeRouter()

      const preload = useScansStore()
      await preload.load()
      const id = preload.results[0].id

      await router.push(`/scans?doc=${id}`)
      await router.isReady()

      // ScansResults' onMounted awaits store.load() + store.openDocument() itself
      // (the test has no promise handle for that) — both go through the mock
      // API's simulated network delay, so fake timers are needed to settle them.
      vi.useFakeTimers()
      mount(ScansResults, { global: { plugins: [i18n, vuetify, router] } })
      await vi.advanceTimersByTimeAsync(500)
      await flushPromises()

      expect(useReaderStore().open).toBe(true)
      expect(useReaderStore().cellId).toBe(id)
    })
  })

  describe('search reader (?cell=)', () => {
    function seedCell(id: string) {
      const cell = useCellStore()
      cell.cache.set(id, {
        cell: { id, content: '# hi', attachments: [] } as any,
        facts: [], tunnels: [],
      })
      return cell
    }

    it('selecting a cell writes ?cell= into the URL; clearing removes it', async () => {
      const router = makeRouter()
      await router.push('/')
      await router.isReady()

      const cell = seedCell('c1')
      mount(KnowledgeReader, { global: { plugins: [i18n, router] } })
      await flushPromises()

      await cell.open({ id: 'c1', content: '# hi', attachments: [] } as any)
      await flushPromises()

      expect(router.currentRoute.value.query.cell).toBe('c1')

      cell.clear()
      await flushPromises()

      expect(router.currentRoute.value.query.cell).toBeUndefined()
    })

    it('mounting /?cell=<id> restores the cell', async () => {
      const router = makeRouter()
      seedCell('c1')

      await router.push('/?cell=c1')
      await router.isReady()

      mount(KnowledgeReader, { global: { plugins: [i18n, router] } })
      await flushPromises()

      expect(useCellStore().currentId).toBe('c1')
    })
  })
})
