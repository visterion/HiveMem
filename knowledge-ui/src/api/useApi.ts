import { MockApiClient } from './mockClient'
import { HttpApiClient } from './httpClient'
import { readEnv } from './env'
import type { ApiClient } from './types'

let client: ApiClient | null = null

export function useApi(): ApiClient {
  if (client) return client
  // Default to the mock under the test runner (Vitest sets MODE=test) so component/
  // store specs never hit the network — a real HttpApiClient would fire a fetch to
  // /mcp (resolved against happy-dom's localhost:3000) and surface as an unhandled
  // ECONNREFUSED rejection, failing `vitest run` despite green assertions.
  const forceMock = readEnv('VITE_USE_MOCK') === 'true'
    || readEnv('MODE') === 'test'
    || localStorage.getItem('hivemem_mock') === 'true'
  if (forceMock) {
    client = new MockApiClient()
  } else {
    // VITE_HIVEMEM_TOKEN is a dev-only escape hatch: it makes the UI reachable from
    // devices with no console to seed localStorage (phones). import.meta.env.DEV is
    // statically false in a production build, so the fallback is tree-shaken away.
    const token = localStorage.getItem('hivemem_token')
      ?? (import.meta.env.DEV ? readEnv('VITE_HIVEMEM_TOKEN') ?? '' : '')
    client = new HttpApiClient({ endpoint: (readEnv('VITE_HIVEMEM_URL') ?? '') + '/mcp', token })
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
