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
})
