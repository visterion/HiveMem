import { describe, it, expect, vi, beforeAll } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DocumentViewer from '../../src/components/readers/DocumentViewer.vue'
import { i18n } from '../../src/i18n'

// Controllable pdf.js mock: 3-page document, render resolves immediately.
const { getPageMock, getDocumentMock } = vi.hoisted(() => {
  const renderMock = vi.fn(() => ({ promise: Promise.resolve() }))
  const getPageMock = vi.fn(async () => ({
    getViewport: ({ scale }: { scale: number }) => ({ width: 600 * scale, height: 800 * scale }),
    render: renderMock,
  }))
  const getDocumentMock = vi.fn(() => ({
    promise: Promise.resolve({ numPages: 3, getPage: getPageMock }),
  }))
  return { getPageMock, getDocumentMock }
})

// happy-dom has no canvas 2d implementation (getContext returns null), which would
// trip the viewer's null-context guard. Stub a minimal context for the pdf tests.
beforeAll(() => {
  HTMLCanvasElement.prototype.getContext = vi.fn(() => ({})) as unknown as typeof HTMLCanvasElement.prototype.getContext
})
vi.mock('pdfjs-dist', () => ({
  GlobalWorkerOptions: { workerSrc: '' },
  getDocument: getDocumentMock,
}))

function mountViewer(props: Record<string, unknown>) {
  return mount(DocumentViewer, {
    props: { kind: 'image', url: '/api/attachments/x/content', filename: 'x.png', ...props },
    global: { plugins: [i18n] },
  })
}

describe('DocumentViewer (image)', () => {
  it('renders an <img> with the attachment url for image kind', () => {
    const w = mountViewer({})
    const img = w.find('img[data-test="dv-image"]')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('/api/attachments/x/content')
  })

  it('zoom-in button increases the scale percent in the toolbar', async () => {
    const w = mountViewer({})
    expect(w.find('[data-test="vt-zoom"]').text()).toContain('100%')
    await w.find('[data-test="vt-zoom-in"]').trigger('click')
    expect(w.find('[data-test="vt-zoom"]').text()).not.toContain('100%')
  })

  it('zoom-out button decreases the scale percent below 100%', async () => {
    const w = mountViewer({})
    expect(w.find('[data-test="vt-zoom"]').text()).toContain('100%')
    await w.find('[data-test="vt-zoom-out"]').trigger('click')
    const txt = w.find('[data-test="vt-zoom"]').text()
    expect(txt).not.toContain('100%')
    expect(txt).toContain('80%') // zoomOut emits zoomBy(0.8) → 80%
  })

  it('shows an error tile with a download link when the image fails to load', async () => {
    const w = mountViewer({})
    await w.find('img[data-test="dv-image"]').trigger('error')
    await flushPromises()
    expect(w.find('[data-test="dv-error"]').exists()).toBe(true)
    const link = w.find('[data-test="dv-error"] a')
    expect(link.attributes('href')).toBe('/api/attachments/x/content')
  })

  it('does not render page controls for a single image', () => {
    const w = mountViewer({})
    expect(w.find('[data-test="vt-pages"]').exists()).toBe(false)
  })
})

describe('DocumentViewer (pdf)', () => {
  function mountPdf() {
    return mount(DocumentViewer, {
      props: { kind: 'pdf', url: '/api/attachments/p/content', filename: 'doc.pdf' },
      global: { plugins: [i18n] },
    })
  }

  it('renders a canvas and shows page controls with the document page count', async () => {
    const w = mountPdf()
    await flushPromises()
    expect(w.find('canvas[data-test="dv-canvas"]').exists()).toBe(true)
    expect(w.find('[data-test="vt-pages"]').text()).toContain('1 / 3')
  })

  it('next advances the page number and re-renders that page', async () => {
    const w = mountPdf()
    await flushPromises()
    getPageMock.mockClear()
    await w.find('[data-test="vt-next"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="vt-pages"]').text()).toContain('2 / 3')
    expect(getPageMock).toHaveBeenLastCalledWith(2)
  })

  it('reloads the document when the url prop changes', async () => {
    const w = mountPdf()
    await flushPromises()
    getDocumentMock.mockClear()
    await w.setProps({ url: '/api/attachments/q/content' })
    await flushPromises()
    expect(getDocumentMock).toHaveBeenCalledTimes(1)
  })

  it('re-renders the pdf at a higher resolution when zoomed in (crisp zoom, not CSS upscale)', async () => {
    vi.useFakeTimers()
    try {
      const w = mountPdf()
      await vi.runOnlyPendingTimersAsync()
      await flushPromises()
      const cv = w.find('canvas[data-test="dv-canvas"]').element as HTMLCanvasElement
      const baseWidth = cv.width
      expect(baseWidth).toBeGreaterThan(0)
      // Zoom in well past the re-render threshold.
      for (let i = 0; i < 4; i++) await w.find('[data-test="vt-zoom-in"]').trigger('click')
      // Let the debounced re-render fire.
      await vi.advanceTimersByTimeAsync(400)
      await flushPromises()
      expect(cv.width).toBeGreaterThan(baseWidth)
    } finally {
      vi.useRealTimers()
    }
  })

  it('a stale render superseded by rapid paging does not clash on the canvas or flag a false error (E5)', async () => {
    // Simulate pdf.js throwing when render() is invoked while a previous render on
    // the same canvas hasn't settled yet — what actually happens if two renderPage()
    // calls both reach pdfPage.render() (the pre-fix bug). With the render-generation
    // guard, only the newest renderPage() call may ever reach render() at all.
    let renderInFlight = false
    const clashSafeRender = vi.fn(() => {
      if (renderInFlight) throw new Error('Cannot use the same canvas during multiple render() operations')
      renderInFlight = true
      return { promise: Promise.resolve().then(() => { renderInFlight = false }) }
    })
    const pending: Array<() => void> = []
    getPageMock.mockImplementation((n: number) => new Promise(resolve => {
      pending.push(() => resolve({
        getViewport: ({ scale }: { scale: number }) => ({ width: 600 * scale, height: 800 * scale }),
        render: clashSafeRender,
      }))
    }))
    const w = mountPdf()
    await flushPromises()
    pending.shift()!() // resolve the initial load's getPage(1)
    await flushPromises()

    // Two rapid page changes: both renderPage() calls start and call getPage()
    // before either's promise resolves.
    await w.find('[data-test="vt-next"]').trigger('click')
    await w.find('[data-test="vt-next"]').trigger('click')
    const [resolveStale, resolveCurrent] = pending.splice(0)
    // Resolve out of order and back-to-back (no await between them) so both
    // continuations are still in flight concurrently — anything else lets the
    // first one fully finish (and reset renderInFlight) before the second starts,
    // which would hide the race this test exists to catch.
    resolveCurrent()
    resolveStale()
    await flushPromises()

    expect(w.find('[data-test="dv-error"]').exists()).toBe(false)
    expect(w.find('[data-test="vt-pages"]').text()).toContain('3 / 3')
  })

  it('clears page controls when switching from a multi-page pdf to an image', async () => {
    const w = mountPdf()
    await flushPromises()
    expect(w.find('[data-test="vt-pages"]').text()).toContain('1 / 3')
    // Switch the same viewer instance to a single-page image attachment.
    await w.setProps({ kind: 'image', url: '/api/attachments/img/content', filename: 'p.png' })
    await flushPromises()
    expect(w.find('[data-test="vt-pages"]').exists()).toBe(false)
  })
})
