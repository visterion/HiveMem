import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { loadAuthMode, authMode, __resetAuthMode } from '../../src/api/authMode'

describe('authMode', () => {
  beforeEach(() => __resetAuthMode())
  afterEach(() => vi.unstubAllGlobals())

  it('resolves to access when /api/config answers authMode: access', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ authMode: 'access' }))))
    const m = await loadAuthMode()
    expect(m).toBe('access')
    expect(authMode()).toBe('access')
  })

  it('resolves to legacy when /api/config answers authMode: legacy', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ authMode: 'legacy' }))))
    const m = await loadAuthMode()
    expect(m).toBe('legacy')
  })

  it('falls back to legacy when the fetch rejects', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))
    const m = await loadAuthMode()
    expect(m).toBe('legacy')
  })

  // Regression (whole-branch e2e review): a slow/unreachable backend (dev-proxy to a down
  // backend, offline page load) must not hang App.vue's startup forever. loadAuthMode()
  // bounds the fetch with AbortSignal.timeout(1500) — an AbortError from that timeout must
  // land in the same catch as any other fetch failure and resolve to 'legacy' quickly.
  it('falls back to legacy fast when the fetch times out / aborts', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new DOMException('The operation was aborted.', 'AbortError')
    }))
    const start = Date.now()
    const m = await loadAuthMode()
    expect(m).toBe('legacy')
    expect(Date.now() - start).toBeLessThan(200)
  })

  it('caches the mode and does not fetch again on a second call', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ authMode: 'access' })))
    vi.stubGlobal('fetch', fetchMock)
    await loadAuthMode()
    await loadAuthMode()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
