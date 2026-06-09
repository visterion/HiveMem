import { beforeEach, describe, expect, it } from 'vitest'
import de from '../../src/i18n/messages/de'
import en from '../../src/i18n/messages/en'
import {
  isLocale, storedLocale, resolveInitialLocale, applyBackendDefault,
  setLocale, i18n, STORAGE_KEY
} from '../../src/i18n'

function flatten(obj: Record<string, any>, prefix = ''): string[] {
  return Object.entries(obj).flatMap(([k, v]) =>
    typeof v === 'object' && v !== null
      ? flatten(v, `${prefix}${k}.`)
      : [`${prefix}${k}`]
  )
}

describe('catalog parity', () => {
  it('de and en have identical key sets', () => {
    const deKeys = flatten(de).sort()
    const enKeys = flatten(en).sort()
    expect(deKeys).toEqual(enKeys)
  })
})

describe('locale helpers', () => {
  beforeEach(() => {
    localStorage.clear()
    i18n.global.locale.value = 'de'
  })

  it('isLocale accepts only de and en', () => {
    expect(isLocale('de')).toBe(true)
    expect(isLocale('en')).toBe(true)
    expect(isLocale('fr')).toBe(false)
    expect(isLocale(null)).toBe(false)
  })

  it('storedLocale returns a valid stored value, else null', () => {
    expect(storedLocale()).toBeNull()
    localStorage.setItem(STORAGE_KEY, 'en')
    expect(storedLocale()).toBe('en')
    localStorage.setItem(STORAGE_KEY, 'fr')
    expect(storedLocale()).toBeNull()
  })

  it('resolveInitialLocale prefers a valid stored value, else de', () => {
    expect(resolveInitialLocale()).toBe('de')
    localStorage.setItem(STORAGE_KEY, 'en')
    expect(resolveInitialLocale()).toBe('en')
  })

  it('setLocale switches the active locale and persists it', () => {
    setLocale('en')
    expect(i18n.global.locale.value).toBe('en')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('en')
  })

  it('applyBackendDefault applies a valid backend locale when nothing is stored', () => {
    applyBackendDefault('en')
    expect(i18n.global.locale.value).toBe('en')
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
  })

  it('applyBackendDefault is ignored when the user has a stored preference', () => {
    localStorage.setItem(STORAGE_KEY, 'de')
    applyBackendDefault('en')
    expect(i18n.global.locale.value).toBe('de')
  })

  it('applyBackendDefault ignores invalid/empty backend values', () => {
    applyBackendDefault('fr')
    expect(i18n.global.locale.value).toBe('de')
    applyBackendDefault(undefined)
    expect(i18n.global.locale.value).toBe('de')
  })
})
