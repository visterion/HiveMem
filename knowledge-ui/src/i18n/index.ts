import { createI18n } from 'vue-i18n'
import { de as vuetifyDe, en as vuetifyEn } from 'vuetify/locale'
import de from './messages/de'
import en from './messages/en'

export const SUPPORTED = ['de', 'en'] as const
export type Locale = (typeof SUPPORTED)[number]
export const STORAGE_KEY = 'hivemem_locale'

export function isLocale(v: unknown): v is Locale {
  return SUPPORTED.includes(v as Locale)
}

export function storedLocale(): Locale | null {
  const v = localStorage.getItem(STORAGE_KEY)
  return isLocale(v) ? v : null
}

export function resolveInitialLocale(): Locale {
  return storedLocale() ?? 'de'
}

export const i18n = createI18n({
  legacy: false,
  locale: resolveInitialLocale(),
  fallbackLocale: 'de',
  messages: {
    de: { ...de, $vuetify: vuetifyDe },
    en: { ...en, $vuetify: vuetifyEn }
  }
})

// Set the active locale and remember the user's explicit choice.
export function setLocale(locale: Locale): void {
  i18n.global.locale.value = locale
  localStorage.setItem(STORAGE_KEY, locale)
}

// Apply the backend default — but only when the user has not chosen a locale.
// A stored preference always wins; invalid/empty backend values are ignored.
export function applyBackendDefault(lang: string | null | undefined): void {
  if (storedLocale()) return
  if (isLocale(lang)) i18n.global.locale.value = lang
}
