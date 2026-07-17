import { MockApiClient } from './mockClient'
import { HttpApiClient } from './httpClient'
import { readEnv } from './env'
import { authMode } from './authMode'
import type { ApiClient } from './types'

let client: ApiClient | null = null

export function useApi(): ApiClient {
  if (client) return client
  // Default to the mock under the test runner (Vitest sets MODE=test) so component/
  // store specs never hit the network — a real HttpApiClient would fire a fetch to
  // /api/tools/call (resolved against happy-dom's localhost:3000) and surface as an unhandled
  // ECONNREFUSED rejection, failing `vitest run` despite green assertions.
  const forceMock = readEnv('VITE_USE_MOCK') === 'true'
    || readEnv('MODE') === 'test'
    || localStorage.getItem('hivemem_mock') === 'true'
  if (forceMock) {
    client = new MockApiClient()
  } else {
    // VITE_HIVEMEM_TOKEN: dev-only escape hatch for console-less devices (phones), and only
    // in legacy mode — in Access mode /api takes no bearer, so a token in the heap is dead
    // weight and an XSS liability. import.meta.env.DEV is statically false in prod builds,
    // so this whole branch is tree-shaken out of production.
    const devToken = (import.meta.env.DEV && authMode() === 'legacy')
      ? readEnv('VITE_HIVEMEM_TOKEN') ?? ''
      : ''
    client = new HttpApiClient({
      endpoint: (readEnv('VITE_HIVEMEM_URL') ?? '') + '/api/tools/call',
      token: devToken,
    })
  }
  if (typeof window !== 'undefined') {
    ;(window as any).__useMock = (flag: boolean) => {
      localStorage.setItem('hivemem_mock', String(flag))
      location.reload()
    }
  }
  return client
}

export function resetApi() { client = null }
