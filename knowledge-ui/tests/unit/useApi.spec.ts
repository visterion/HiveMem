import { describe, it, expect, vi, beforeEach } from 'vitest'

const captured: { endpoint?: string; token?: string } = {}

vi.mock('../../src/api/httpClient', () => ({
  HttpApiClient: class {
    constructor(opts: { endpoint: string; token: string }) {
      captured.endpoint = opts.endpoint
      captured.token = opts.token
    }
  },
}))

const envBag: Record<string, string | undefined> = {}
vi.mock('../../src/api/env', () => ({
  readEnv: (key: string) => envBag[key],
}))

import { useApi, resetApi } from '../../src/api/useApi'

describe('useApi token resolution', () => {
  beforeEach(() => {
    resetApi()
    localStorage.clear()
    for (const k of Object.keys(envBag)) delete envBag[k]
    captured.endpoint = undefined
    captured.token = undefined
  })

  it('prefers the token from localStorage', () => {
    envBag.VITE_HIVEMEM_TOKEN = 'from-env'
    localStorage.setItem('hivemem_token', 'from-storage')
    useApi()
    expect(captured.token).toBe('from-storage')
  })

  it('falls back to VITE_HIVEMEM_TOKEN in dev when localStorage is empty', () => {
    envBag.VITE_HIVEMEM_TOKEN = 'from-env'
    useApi()
    expect(captured.token).toBe('from-env')
  })

  it('yields an empty token when neither source provides one', () => {
    useApi()
    expect(captured.token).toBe('')
  })
})
