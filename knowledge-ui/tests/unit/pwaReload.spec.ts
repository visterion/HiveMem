import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUiStore } from '../../src/stores/ui'

describe('ui store — SW update', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('setSwUpdate marks ready and stores the updater', () => {
    const ui = useUiStore()
    expect(ui.swUpdateReady).toBe(false)
    const fn = vi.fn()
    ui.setSwUpdate(fn)
    expect(ui.swUpdateReady).toBe(true)
    ui.applySwUpdate()
    expect(fn).toHaveBeenCalledOnce()
  })
})
