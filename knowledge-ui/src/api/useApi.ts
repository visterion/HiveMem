import { MockApiClient } from './mockClient'
import { HttpApiClient } from './httpClient'
import { readEnv } from './env'
import type { ApiClient } from './types'

let client: ApiClient | null = null

export function useApi(): ApiClient {
  if (client) return client
  const forceMock = readEnv('VITE_USE_MOCK') === 'true'
    || localStorage.getItem('hivemem_mock') === 'true'
  if (forceMock) {
    client = new MockApiClient()
  } else {
    const token = localStorage.getItem('hivemem_token') ?? ''
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
