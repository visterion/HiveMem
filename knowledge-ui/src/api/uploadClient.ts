export interface UploadResult { cellId: string; deduplicated: boolean }
export interface UploadTarget { realm: string; signal?: string; topic?: string; cellId?: string }

export class UploadError extends Error {
  readonly status: number
  readonly retryable: boolean
  constructor(status: number, message: string, retryable: boolean) {
    super(message)
    this.name = 'UploadError'
    this.status = status
    this.retryable = retryable
  }
}

/** Cloudflare rejects bodies above ~100 MB with its own HTML error, so a larger file
 *  never reaches the backend cleanly — reject it client-side first. */
export const MAX_UPLOAD_BYTES = 100 * 1024 * 1024

function messageFor(status: number, body: unknown): string {
  const rec = (body ?? {}) as Record<string, unknown>
  const fromBody =
    typeof rec.error === 'string' ? rec.error
    : typeof rec.detail === 'string' ? rec.detail
    : typeof rec.message === 'string' ? rec.message
    : null
  if (fromBody) return fromBody
  switch (status) {
    case 401: return 'Session expired'
    case 403: return 'No write permission'
    case 413: return 'File too large'
    case 503: return 'Attachment storage is disabled'
    default: return `Upload failed (HTTP ${status})`
  }
}

// 401 → re-login then retry; transient 5xx → retry. 400/403/413/503 are terminal.
function retryableFor(status: number): boolean {
  return status === 401 || (status >= 500 && status !== 503)
}

export function uploadAttachment(
  file: File,
  target: UploadTarget,
  onProgress?: (fraction: number) => void,
): Promise<UploadResult> {
  return new Promise((resolve, reject) => {
    const form = new FormData()
    form.append('file', file)
    form.append('realm', target.realm)
    if (target.signal) form.append('signal', target.signal)
    if (target.topic) form.append('topic', target.topic)
    if (target.cellId) form.append('cell_id', target.cellId)

    const xhr = new XMLHttpRequest()
    xhr.open('POST', '/api/attachments')
    // Same-origin XHR sends the session cookie automatically; no withCredentials, and
    // deliberately NO Authorization header (/api is cookie-only).
    xhr.responseType = 'text'
    if (onProgress) {
      xhr.upload.onprogress = (e: ProgressEvent) => {
        if (e.lengthComputable) onProgress(e.loaded / e.total)
      }
    }
    xhr.onload = () => {
      let body: unknown = null
      try { body = xhr.responseText ? JSON.parse(xhr.responseText) : null } catch { /* non-JSON */ }
      if (xhr.status >= 200 && xhr.status < 300) {
        const rec = (body ?? {}) as Record<string, unknown>
        resolve({ cellId: String(rec.cell_id ?? ''), deduplicated: rec.deduplicated === true })
      } else {
        reject(new UploadError(xhr.status, messageFor(xhr.status, body), retryableFor(xhr.status)))
      }
    }
    xhr.onerror = () =>
      reject(new UploadError(0, 'Network error during upload (the file may be too large)', true))
    xhr.send(form)
  })
}
