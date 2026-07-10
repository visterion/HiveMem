import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../../src/stores/auth'
import { useUiStore } from '../../src/stores/ui'
import { useCanvasStore } from '../../src/stores/canvas'
import { MockApiClient } from '../../src/api/mockClient'
import { resetApi } from '../../src/api/useApi'

describe('stores', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('auth starts logged out', () => {
    const s = useAuthStore()
    expect(s.isAuthenticated).toBe(false)
    expect(s.role).toBe(null)
  })

  it('ui defaults: dark theme, no panel open, size metric = count', () => {
    const s = useUiStore()
    expect(s.activePanel).toBe(null)
    expect(s.sizeMetric).toBe('cell_count')
    expect(s.theme).toBe('dark')
  })

  it('canvas loadTopLevel populates realms + cells from api', async () => {
    localStorage.setItem('hivemem_mock', 'true'); resetApi()
    const s = useCanvasStore()
    await s.loadTopLevel()
    expect(s.realms.length).toBeGreaterThan(0)
    // streaming is async (long-poll); wait for the first batch to arrive
    for (let i = 0; i < 60 && s.cells.length === 0; i++) {
      await new Promise(r => setTimeout(r, 50))
    }
    expect(s.cells.length).toBeGreaterThan(0)
    s.stopStream()
  })

  it('canvas loadTopLevel guards against concurrent double-runs (M56)', async () => {
    localStorage.setItem('hivemem_mock', 'true'); resetApi()
    const spy = vi.spyOn(MockApiClient.prototype, 'call')
    const s = useCanvasStore()
    // Two mounts in quick succession must not double-load (would duplicate cells/tunnels)
    await Promise.all([s.loadTopLevel(), s.loadTopLevel()])
    const listCalls = spy.mock.calls.filter(c => c[0] === 'list').length
    expect(listCalls).toBe(1)
    // While the stream is still active, another call must also be a no-op
    if (s.streamActive) {
      await s.loadTopLevel()
      expect(spy.mock.calls.filter(c => c[0] === 'list').length).toBe(1)
    }
    s.stopStream()
    spy.mockRestore()
  })
})
