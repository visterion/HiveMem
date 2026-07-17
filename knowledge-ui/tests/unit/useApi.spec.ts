import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { useApi, resetApi } from '../../src/api/useApi'
import { HttpApiClient } from '../../src/api/httpClient'
import { __resetAuthMode } from '../../src/api/authMode'

// useApi() forces the MockApiClient whenever MODE === 'test' (see useApi.ts) so plain
// `useApi()` calls never exercise HttpApiClient under vitest. These specs bypass that
// guard by stubbing VITE_USE_MOCK=false and constructing the real client directly via
// the same config useApi() would build, to pin down the endpoint + header contract.

describe('useApi / HttpApiClient wiring', () => {
  beforeEach(() => {
    resetApi()
    __resetAuthMode()
    localStorage.removeItem('hivemem_mock')
    localStorage.removeItem('hivemem_token')
  })
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.restoreAllMocks()
  })

  it('talks to /api/tools/call and sets no Authorization header in access mode', async () => {
    vi.stubEnv('VITE_USE_MOCK', 'false')
    vi.stubEnv('MODE', 'production')
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ authMode: 'access' }))))
    const authModeMod = await import('../../src/api/authMode')
    await authModeMod.loadAuthMode()

    let capturedUrl = ''
    let capturedHeaders: Record<string, string> = {}
    const fetchMock = vi.fn(async (url: string, init: RequestInit) => {
      capturedUrl = url
      capturedHeaders = init.headers as Record<string, string>
      return new Response(JSON.stringify({
        jsonrpc: '2.0', id: 1, result: { content: [{ type: 'text', text: '{}' }] },
      }))
    })
    vi.stubGlobal('fetch', fetchMock)

    const api = useApi()
    expect(api).toBeInstanceOf(HttpApiClient)
    await api.call('status')
    expect(capturedUrl).toBe('/api/tools/call')
    expect(capturedHeaders['Authorization']).toBeUndefined()
  })

  it('does not attach the VITE_HIVEMEM_TOKEN escape hatch outside dev + legacy mode', async () => {
    vi.stubEnv('VITE_USE_MOCK', 'false')
    vi.stubEnv('MODE', 'production')
    vi.stubEnv('DEV', false)
    vi.stubEnv('VITE_HIVEMEM_TOKEN', 'dev-token')
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ authMode: 'legacy' }))))
    const authModeMod = await import('../../src/api/authMode')
    await authModeMod.loadAuthMode()

    let capturedHeaders: Record<string, string> = {}
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      capturedHeaders = init.headers as Record<string, string>
      return new Response(JSON.stringify({
        jsonrpc: '2.0', id: 1, result: { content: [{ type: 'text', text: '{}' }] },
      }))
    }))

    const api = useApi()
    await api.call('status')
    // DEV is statically false here (mirrors a prod build) so the escape hatch must be dead.
    expect(capturedHeaders['Authorization']).toBeUndefined()
  })

  it('attaches the escape hatch token when both DEV and legacy mode are true', async () => {
    vi.stubEnv('VITE_USE_MOCK', 'false')
    vi.stubEnv('MODE', 'production')
    vi.stubEnv('DEV', true)
    vi.stubEnv('VITE_HIVEMEM_TOKEN', 'dev-token')
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ authMode: 'legacy' }))))
    const authModeMod = await import('../../src/api/authMode')
    await authModeMod.loadAuthMode()

    let capturedHeaders: Record<string, string> = {}
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      capturedHeaders = init.headers as Record<string, string>
      return new Response(JSON.stringify({
        jsonrpc: '2.0', id: 1, result: { content: [{ type: 'text', text: '{}' }] },
      }))
    }))

    const api = useApi()
    await api.call('status')
    expect(capturedHeaders['Authorization']).toBe('Bearer dev-token')
  })
})
