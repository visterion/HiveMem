import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

// Controllable mock of the client so we can drive ordering/errors deterministically.
const deferreds: Array<{ resolve: (v: any) => void; reject: (e: any) => void }> = []
vi.mock('../../src/api/uploadClient', async () => {
  const actual = await vi.importActual<any>('../../src/api/uploadClient')
  return {
    ...actual,
    uploadAttachment: vi.fn(() => new Promise((resolve, reject) => { deferreds.push({ resolve, reject }) })),
  }
})
import { useUploadsStore } from '../../src/stores/uploads'
import { UploadError } from '../../src/api/uploadClient'

const flush = () => new Promise(r => setTimeout(r, 0))

describe('uploads store', () => {
  beforeEach(() => { setActivePinia(createPinia()); deferreds.length = 0 })

  it('processes jobs sequentially — second starts only after first settles', async () => {
    const s = useUploadsStore()
    s.enqueue([new File(['a'], 'a.pdf'), new File(['b'], 'b.pdf')])
    await flush()
    expect(deferreds.length).toBe(1)                       // only first started
    expect(s.jobs[0].status).toBe('uploading')
    expect(s.jobs[1].status).toBe('queued')
    deferreds[0].resolve({ cellId: 'c1', deduplicated: false })
    await flush()
    expect(s.jobs[0].status).toBe('done')
    expect(deferreds.length).toBe(2)                       // now second started
  })

  it('an errored job does not block the rest of the queue', async () => {
    const s = useUploadsStore()
    s.enqueue([new File(['a'], 'a.pdf'), new File(['b'], 'b.pdf')])
    await flush()
    deferreds[0].reject(new UploadError(400, 'bad', false))
    await flush()
    expect(s.jobs[0].status).toBe('error')
    deferreds[1].resolve({ cellId: 'c2', deduplicated: false })
    await flush()
    expect(s.jobs[1].status).toBe('done')
  })

  it('401 fails the remaining queue without a redirect', async () => {
    const s = useUploadsStore()
    s.enqueue([new File(['a'], 'a.pdf'), new File(['b'], 'b.pdf')])
    await flush()
    deferreds[0].reject(new UploadError(401, 'Session expired', true))
    await flush()
    expect(s.authError).toBe(true)
    expect(s.jobs[0].status).toBe('error')
    expect(s.jobs[1].status).toBe('error')                // not left queued/uploading
    expect(deferreds.length).toBe(1)                      // second never started
  })

  it('rejects oversize files client-side without calling the client', async () => {
    const s = useUploadsStore()
    const big = new File(['x'], 'big.bin')
    Object.defineProperty(big, 'size', { value: 200 * 1024 * 1024 })
    s.enqueue([big])
    await flush()
    expect(s.jobs[0].status).toBe('error')
    expect(s.jobs[0].errorKey).toBe('upload.tooLarge')
    expect(deferreds.length).toBe(0)
  })

  it('retry re-queues an errored job', async () => {
    const s = useUploadsStore()
    s.enqueue([new File(['a'], 'a.pdf')])
    await flush()
    deferreds[0].reject(new UploadError(500, 'boom', true))
    await flush()
    expect(s.jobs[0].status).toBe('error')
    s.retry(s.jobs[0].id)
    await flush()
    expect(s.jobs[0].status).toBe('uploading')
  })

  it('maps error statuses to i18n errorKey', async () => {
    const s = useUploadsStore()
    s.enqueue([new File(['a'], 'a.pdf'), new File(['b'], 'b.pdf'), new File(['c'], 'c.pdf'),
      new File(['d'], 'd.pdf'), new File(['e'], 'e.pdf')])
    await flush()
    deferreds[0].reject(new UploadError(403, 'forbidden', false))
    await flush()
    expect(s.jobs[0].errorKey).toBe('upload.errForbidden')
    deferreds[1].reject(new UploadError(413, 'too big', false))
    await flush()
    expect(s.jobs[1].errorKey).toBe('upload.tooLarge')
    deferreds[2].reject(new UploadError(503, 'storage off', false))
    await flush()
    expect(s.jobs[2].errorKey).toBe('upload.errStorage')
    deferreds[3].reject(new UploadError(400, 'bad', false))
    await flush()
    expect(s.jobs[3].errorKey).toBe('upload.errBad')
    deferreds[4].reject(new UploadError(500, 'boom', true))
    await flush()
    expect(s.jobs[4].errorKey).toBe('upload.errGeneric')
  })

  it('flags 401 failures with authFailure instead of matching the message string', async () => {
    const s = useUploadsStore()
    s.enqueue([new File(['a'], 'a.pdf'), new File(['b'], 'b.pdf')])
    await flush()
    deferreds[0].reject(new UploadError(401, 'Session expired', true))
    await flush()
    expect(s.jobs[0].authFailure).toBe(true)
    expect(s.jobs[0].errorKey).toBe('upload.errSession')
    expect(s.jobs[1].authFailure).toBe(true)               // sibling flipped in the 401 branch
    // retry recomputes authError purely from the authFailure flag
    s.jobs[0].error = 'something else entirely'
    s.retry(s.jobs[0].id)
    expect(s.authError).toBe(true)
  })

  it('treats a non-UploadError rejection as a generic retryable failure', async () => {
    const s = useUploadsStore()
    s.enqueue([new File(['a'], 'a.pdf')])
    await flush()
    deferreds[0].reject(new Error('boom'))
    await flush()
    expect(s.jobs[0].status).toBe('error')
    expect(s.jobs[0].retryable).toBe(true)
    expect(s.jobs[0].errorKey).toBe('upload.errGeneric')
    expect(s.jobs[0].error).toBe('boom')
    expect(s.authError).toBe(false)
  })
})
