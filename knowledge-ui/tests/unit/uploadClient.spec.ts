import { afterEach, describe, expect, it, vi } from 'vitest'
import { uploadAttachment, UploadError } from '../../src/api/uploadClient'

// Minimal controllable XHR fake.
class FakeXhr {
  static last: FakeXhr
  upload = { onprogress: null as null | ((e: any) => void) }
  onload: null | (() => void) = null
  onerror: null | (() => void) = null
  status = 0
  responseText = ''
  responseType = ''
  method = ''; url = ''
  headers: Record<string, string> = {}
  body: any = null
  constructor() { FakeXhr.last = this }
  open(m: string, u: string) { this.method = m; this.url = u }
  setRequestHeader(k: string, v: string) { this.headers[k] = v }
  send(b: any) { this.body = b }
  respond(status: number, text = '') { this.status = status; this.responseText = text; this.onload?.() }
}

afterEach(() => vi.unstubAllGlobals())

function withFakeXhr() { vi.stubGlobal('XMLHttpRequest', FakeXhr as unknown as typeof XMLHttpRequest) }

describe('uploadAttachment', () => {
  it('POSTs multipart to /api/attachments with realm and no Authorization header', async () => {
    withFakeXhr()
    const file = new File(['hi'], 'note.pdf', { type: 'application/pdf' })
    const p = uploadAttachment(file, { realm: 'inbox' })
    const xhr = FakeXhr.last
    expect(xhr.method).toBe('POST')
    expect(xhr.url).toBe('/api/attachments')
    expect(xhr.headers['Authorization']).toBeUndefined()
    expect(xhr.body).toBeInstanceOf(FormData)
    expect((xhr.body as FormData).get('realm')).toBe('inbox')
    xhr.respond(201, JSON.stringify({ cell_id: 'c1', deduplicated: false }))
    await expect(p).resolves.toEqual({ cellId: 'c1', deduplicated: false })
  })

  it('maps 403 to a non-retryable UploadError', async () => {
    withFakeXhr()
    const p = uploadAttachment(new File(['x'], 'a.png'), { realm: 'inbox' })
    FakeXhr.last.respond(403, JSON.stringify({ message: 'forbidden' }))
    await expect(p).rejects.toMatchObject({ status: 403, retryable: false })
  })

  it('maps 401 to a retryable UploadError', async () => {
    withFakeXhr()
    const p = uploadAttachment(new File(['x'], 'a.png'), { realm: 'inbox' })
    FakeXhr.last.respond(401)
    await expect(p).rejects.toMatchObject({ status: 401, retryable: true })
  })

  it('extracts the message from any of error|detail|message', async () => {
    withFakeXhr()
    const p = uploadAttachment(new File(['x'], 'a.png'), { realm: 'inbox' })
    FakeXhr.last.respond(503, JSON.stringify({ error: 'storage off' }))
    await expect(p).rejects.toThrow('storage off')
  })

  it('treats a network error as a retryable failure', async () => {
    withFakeXhr()
    const p = uploadAttachment(new File(['x'], 'a.png'), { realm: 'inbox' })
    FakeXhr.last.onerror?.()
    await expect(p).rejects.toMatchObject({ status: 0, retryable: true })
  })
})
