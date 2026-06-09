import { describe, it, expect } from 'vitest'
import { mimeKind, buildAttachmentTabs } from '../../src/components/readers/attachmentTabs'

describe('mimeKind', () => {
  it('maps known MIME types', () => {
    expect(mimeKind('application/pdf')).toBe('pdf')
    expect(mimeKind('image/jpeg')).toBe('image')
    expect(mimeKind('image/png')).toBe('image')
    expect(mimeKind('message/rfc822')).toBe('eml')
  })
  it('falls back to other for unknown/empty', () => {
    expect(mimeKind('application/zip')).toBe('other')
    expect(mimeKind('')).toBe('other')
  })
})

describe('buildAttachmentTabs', () => {
  it('maps attachments to tabs with content URLs and kinds', () => {
    const tabs = buildAttachmentTabs([
      { id: 'a1', mime_type: 'application/pdf', original_filename: 'huk.pdf', size_bytes: 10 },
      { id: 'a2', mime_type: 'image/png', original_filename: 'scan.png', size_bytes: 20 },
    ])
    expect(tabs).toEqual([
      { id: 'a1', title: 'huk.pdf', url: '/api/attachments/a1/content', kind: 'pdf' },
      { id: 'a2', title: 'scan.png', url: '/api/attachments/a2/content', kind: 'image' },
    ])
  })
  it('returns [] for undefined or empty input', () => {
    expect(buildAttachmentTabs(undefined)).toEqual([])
    expect(buildAttachmentTabs([])).toEqual([])
  })
  it('uses a fallback title when filename missing', () => {
    const tabs = buildAttachmentTabs([
      { id: 'a3', mime_type: 'application/pdf', original_filename: '', size_bytes: 0 },
    ])
    expect(tabs[0].title).toBe('Original')
  })
})
