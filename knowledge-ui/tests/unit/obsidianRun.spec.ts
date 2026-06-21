import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCellStore } from '../../src/stores/cell'
import { resetApi, useApi } from '../../src/api/useApi'
import { buildPlan } from '../../src/composables/obsidianImport'
import type { Cell } from '../../src/api/types'

describe('cell store importObsidian()', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
  })

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
})
