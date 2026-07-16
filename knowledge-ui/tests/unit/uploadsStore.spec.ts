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
})
