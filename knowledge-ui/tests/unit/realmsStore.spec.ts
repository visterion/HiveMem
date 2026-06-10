import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useRealmsStore } from '../../src/stores/realms'
import { resetApi } from '../../src/api/useApi'

describe('realms store', () => {
  beforeEach(() => {
    setActivePinia(createPinia()); localStorage.clear()
    localStorage.setItem('hivemem_mock', 'true'); resetApi(); vi.useFakeTimers()
  })
  afterEach(() => vi.useRealTimers())

  it('loadRealms merges facet counts with meta, sorted by count desc', async () => {
    const s = useRealmsStore()
    const p = s.loadRealms(); await vi.advanceTimersByTimeAsync(300); await p
    expect(s.realms.length).toBeGreaterThan(1)
    for (let i = 1; i < s.realms.length; i++) {
      expect(s.realms[i - 1].count).toBeGreaterThanOrEqual(s.realms[i].count)
    }
    const docs = s.realms.find(r => r.id === 'documents')
    expect(docs).toBeDefined()
    expect(docs!.priv).toBe('cloud')
    expect(docs!.desc.length).toBeGreaterThan(0)
    expect(docs!.color).toBe('var(--r-docs)')
  })

  it('unknown realms fall back to cloud + empty desc', async () => {
    const s = useRealmsStore()
    const p = s.loadRealms(); await vi.advanceTimersByTimeAsync(300); await p
    const unknown = s.realms.find(r => r.id === 'Databases')
    expect(unknown).toBeDefined()
    expect(unknown!.priv).toBe('cloud')
    expect(unknown!.desc).toBe('')
  })

  it('maxCount getter returns the largest realm count', async () => {
    const s = useRealmsStore()
    const p = s.loadRealms(); await vi.advanceTimersByTimeAsync(300); await p
    expect(s.maxCount).toBe(Math.max(...s.realms.map(r => r.count)))
  })

  it('loadRealms is idempotent (skips when already loaded)', async () => {
    const s = useRealmsStore()
    const p = s.loadRealms(); await vi.advanceTimersByTimeAsync(300); await p
    const first = s.realms
    const p2 = s.loadRealms(); await vi.advanceTimersByTimeAsync(300); await p2
    expect(s.realms).toBe(first)
  })
})
