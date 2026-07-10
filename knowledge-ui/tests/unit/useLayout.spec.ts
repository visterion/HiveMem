import { describe, it, expect, beforeEach, vi } from 'vitest'
import { effectScope } from 'vue'
import { useLayout } from '../../src/composables/useLayout'

function stubMatchMedia(matches: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches, media: query, addEventListener: () => {}, removeEventListener: () => {},
    addListener: () => {}, removeListener: () => {}, onchange: null, dispatchEvent: () => false,
  }))
}

describe('useLayout', () => {
  beforeEach(() => vi.unstubAllGlobals())

  it('isMobile = true when (max-width:959px) matches', () => {
    stubMatchMedia(true)
    expect(useLayout().isMobile.value).toBe(true)
  })
  it('isMobile = false when it does not match (desktop)', () => {
    stubMatchMedia(false)
    expect(useLayout().isMobile.value).toBe(false)
  })
  it('defaults to desktop when matchMedia is unavailable', () => {
    vi.stubGlobal('matchMedia', undefined)
    expect(useLayout().isMobile.value).toBe(false)
  })

  it('reacts to media query changes and removes the listener on scope dispose (L-F11)', () => {
    const listeners = new Set<() => void>()
    const mql = {
      matches: false, media: '(max-width: 959px)',
      addEventListener: (_t: string, fn: () => void) => { listeners.add(fn) },
      removeEventListener: (_t: string, fn: () => void) => { listeners.delete(fn) },
    }
    vi.stubGlobal('matchMedia', () => mql)

    const scope = effectScope()
    const { isMobile } = scope.run(() => useLayout())!
    expect(listeners.size).toBe(1)
    expect(isMobile.value).toBe(false)

    mql.matches = true
    listeners.forEach(fn => fn())
    expect(isMobile.value).toBe(true)

    scope.stop()
    expect(listeners.size).toBe(0) // no leaked matchMedia listener
  })

  it('outside a scope it still registers without crashing', () => {
    const listeners = new Set<() => void>()
    vi.stubGlobal('matchMedia', () => ({
      matches: false, media: '(max-width: 959px)',
      addEventListener: (_t: string, fn: () => void) => { listeners.add(fn) },
      removeEventListener: (_t: string, fn: () => void) => { listeners.delete(fn) },
    }))
    expect(useLayout().isMobile.value).toBe(false)
    expect(listeners.size).toBe(1)
  })
})
