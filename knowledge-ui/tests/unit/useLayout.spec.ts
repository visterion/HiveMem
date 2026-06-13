import { describe, it, expect, beforeEach, vi } from 'vitest'
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
})
