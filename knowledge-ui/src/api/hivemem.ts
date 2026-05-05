// Legacy facade retained so existing hive/*.vue prototype files keep importing
// familiar names. Task 5 will replace this with the useApi composable, and
// Task 22 will rewire hive/*.vue to the new MockApiClient / HttpApiClient.
import { palace } from '../data/mock'
import { readEnv } from './env'
import type { Cell } from './types'

export interface HivememApi {
  getPalace(): Promise<typeof palace>
  getCell(id: string): Promise<Cell>
  search(query: string, limit?: number): Promise<Cell[]>
}

export function createMockClient(): HivememApi {
  return {
    async getPalace() { return palace },
    async getCell(id: string) {
      const cell = palace.cells.find((c) => c.id === id)
      if (!cell) throw new Error(`Cell not found: ${id}`)
      return cell
    },
    async search(query: string, limit = 20) {
      const q = query.toLowerCase()
      return palace.cells
        .filter((c) => c.title.toLowerCase().includes(q) || (c.summary ?? '').toLowerCase().includes(q))
        .slice(0, limit)
    },
  }
}

export function createHttpClient(_url: string, _token: string): HivememApi {
  throw new Error('HTTP client is deferred to Phase 2')
}

function selectClient(): HivememApi {
  const url = readEnv('VITE_HIVEMEM_URL')
  const token = readEnv('VITE_HIVEMEM_TOKEN')
  if (url && token) {
    console.warn('[hivemem] HTTP client not implemented in Phase 1, using mock')
  }
  return createMockClient()
}

export const api: HivememApi = selectClient()
