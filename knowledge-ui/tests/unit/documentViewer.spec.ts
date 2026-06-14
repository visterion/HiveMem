import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DocumentViewer from '../../src/components/readers/DocumentViewer.vue'
import { i18n } from '../../src/i18n'

// pdf.js is dynamically imported only on the pdf path; mock it so the module
// graph resolves even when these image-path tests run.
vi.mock('pdfjs-dist', () => ({ GlobalWorkerOptions: {}, getDocument: vi.fn() }))

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
