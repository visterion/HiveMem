import type { Attachment } from '../../api/types'

export type AttachmentKind = 'pdf' | 'image' | 'eml' | 'other'

export interface AttachmentTab {
  id: string
  title: string
  url: string
  kind: AttachmentKind
}

export function mimeKind(mime: string): AttachmentKind {
  if (mime === 'application/pdf') return 'pdf'
  if (mime.startsWith('image/')) return 'image'
  if (mime === 'message/rfc822') return 'eml'
  return 'other'
}

export function buildAttachmentTabs(attachments: Attachment[] | undefined): AttachmentTab[] {
  if (!attachments) return []
  return attachments.map(a => ({
    id: a.id,
    title: a.original_filename || 'Original',
    url: `/api/attachments/${a.id}/content`,
    kind: mimeKind(a.mime_type),
  }))
}
