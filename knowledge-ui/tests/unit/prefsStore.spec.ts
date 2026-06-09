import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { usePrefsStore } from '../../src/stores/prefs'

describe('prefs store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
    document.documentElement.removeAttribute('data-density')
    document.documentElement.removeAttribute('data-hive')
    document.documentElement.style.cssText = ''
  })

  it('applies defaults to <html> on init', () => {
    const p = usePrefsStore()
    p.init()
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(document.documentElement.getAttribute('data-density')).toBe('regular')
    expect(document.documentElement.getAttribute('data-hive')).toBe('spürbar')
  })

  it('setTheme updates data-theme and persists', () => {
    const p = usePrefsStore()
    p.init()
    p.setTheme('light')
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    expect(JSON.parse(localStorage.getItem('hivemem_prefs')!).theme).toBe('light')
  })

  it('setAccent updates the --honey CSS vars and persists', () => {
    const p = usePrefsStore()
    p.init()
    p.setAccent('#46D6E0')
    expect(document.documentElement.style.getPropertyValue('--honey').trim()).toBe('#46D6E0')
    expect(document.documentElement.style.getPropertyValue('--honey-bright').trim()).toBe('#7FE8EF')
    expect(JSON.parse(localStorage.getItem('hivemem_prefs')!).accent).toBe('#46D6E0')
  })

  it('setDensity and setHive update data-attrs', () => {
    const p = usePrefsStore()
    p.init()
    p.setDensity('compact')
    p.setHive('stark')
    expect(document.documentElement.getAttribute('data-density')).toBe('compact')
    expect(document.documentElement.getAttribute('data-hive')).toBe('stark')
  })

  it('rehydrates persisted prefs on init', () => {
    localStorage.setItem('hivemem_prefs', JSON.stringify({ theme: 'light', accent: '#9BCB3C', density: 'comfy', hive: 'subtil' }))
    const p = usePrefsStore()
    p.init()
    expect(p.theme).toBe('light')
    expect(p.accent).toBe('#9BCB3C')
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    expect(document.documentElement.getAttribute('data-density')).toBe('comfy')
  })
})
