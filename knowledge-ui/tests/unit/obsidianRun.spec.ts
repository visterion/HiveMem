import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCellStore } from '../../src/stores/cell'
import { resetApi, useApi } from '../../src/api/useApi'
import { MockApiClient } from '../../src/api/mockClient'
import { buildPlan } from '../../src/composables/obsidianImport'
import type { Cell } from '../../src/api/types'

describe('cell store importObsidian()', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
  })
  afterEach(() => vi.restoreAllMocks())

  it('creates a cell per note, stubs for missing link targets, and tunnels', async () => {
    const files = [
      { name: 'A.md', text: '---\ntags: [imported]\n---\nlinks to [[B]] and [[Ghost]]' },
      { name: 'B.md', text: 'plain B note #b' },
    ]
    const plan = buildPlan(files)
    const store = useCellStore()

    const seen: number[] = []
    const res = await store.importObsidian(plan.notes, {
      defaultRealm: 'obsidian-test',
      onProgress: (done) => seen.push(done),
    })

    expect(res.cellsCreated).toBe(2)
    expect(res.stubsCreated).toBe(1)   // Ghost
    expect(res.tunnelsCreated).toBe(2) // A→B, A→Ghost
    expect(seen[seen.length - 1]).toBe(2) // progress reached total notes
  })

  it('assigns the default realm and preserves tags + valid_from on imported notes', async () => {
    const files = [{ name: 'Note.md', text: '---\ncreated: 2024-05-05\ntags: [alpha]\n---\nbody' }]
    const plan = buildPlan(files)
    const store = useCellStore()
    await store.importObsidian(plan.notes, { defaultRealm: 'my-vault' })

    const hits = await useApi().call<Cell[]>('search', { query: 'body' })
    const imported = hits.find(c => c.topic === 'Note')
    expect(imported).toBeTruthy()
    expect(imported!.realm).toBe('my-vault')
    expect(imported!.tags).toContain('alpha')
    expect(imported!.valid_from).toBe('2024-05-05')
  })

  it('continues past a failing note and reports the failure count (L-F12)', async () => {
    const files = [
      { name: 'A.md', text: 'links to [[B]]' },
      { name: 'B.md', text: 'this note will fail' },
      { name: 'C.md', text: 'fine' },
    ]
    const plan = buildPlan(files)
    const store = useCellStore()

    const orig = MockApiClient.prototype.call
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(function (
      this: MockApiClient, tool: string, args?: Record<string, unknown>,
    ) {
      // Fail every attempt to create "B" (the note itself and the later stub attempt).
      if (tool === 'add_cell' && args?.topic === 'B') return Promise.reject(new Error('boom'))
      return orig.call(this, tool, args)
    })

    const res = await store.importObsidian(plan.notes, { defaultRealm: 'r' })
    expect(res.cellsCreated).toBe(2)      // A + C survived
    expect(res.failed).toBe(2)            // note B + the A→B link (stub creation also fails)
    expect(res.tunnelsCreated).toBe(0)
    expect(res.stubsCreated).toBe(0)
  })
})
