import { defineStore } from 'pinia'

export type Theme = 'dark' | 'light'
export type Density = 'compact' | 'regular' | 'comfy'
export type Hive = 'subtil' | 'spürbar' | 'stark'

const STORAGE_KEY = 'hivemem_prefs'

// accent hex -> derived bright/deep ramp (ported from hm-app.jsx ACCENTS)
export const ACCENTS: Record<string, { bright: string; deep: string }> = {
  '#F4B740': { bright: '#FFCB5E', deep: '#C98A1E' }, // honey
  '#46D6E0': { bright: '#7FE8EF', deep: '#1F97A1' }, // cyber teal
  '#E0A434': { bright: '#F2BE55', deep: '#A8761A' }, // amber
  '#9BCB3C': { bright: '#BBE05E', deep: '#6E951F' }, // hive green
}

interface PrefsState { theme: Theme; accent: string; density: Density; hive: Hive }

const DEFAULTS: PrefsState = { theme: 'dark', accent: '#F4B740', density: 'regular', hive: 'spürbar' }

function load(): PrefsState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { ...DEFAULTS }
    return { ...DEFAULTS, ...JSON.parse(raw) }
  } catch { return { ...DEFAULTS } }
}

export const usePrefsStore = defineStore('prefs', {
  state: (): PrefsState => ({ ...DEFAULTS }),
  actions: {
    init() {
      Object.assign(this, load())
      this.apply()
    },
    apply() {
      const root = document.documentElement
      root.setAttribute('data-theme', this.theme)
      root.setAttribute('data-density', this.density)
      root.setAttribute('data-hive', this.hive)
      const a = ACCENTS[this.accent] || ACCENTS['#F4B740']
      if (this.theme === 'dark') {
        root.style.setProperty('--honey', this.accent)
        root.style.setProperty('--honey-bright', a.bright)
        root.style.setProperty('--honey-deep', a.deep)
      } else {
        root.style.setProperty('--honey', a.deep)
        root.style.setProperty('--honey-bright', this.accent)
        root.style.setProperty('--honey-deep', a.deep)
      }
    },
    persist() {
      const { theme, accent, density, hive } = this
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ theme, accent, density, hive }))
    },
    setTheme(v: Theme) { this.theme = v; this.apply(); this.persist() },
    setAccent(v: string) { this.accent = v; this.apply(); this.persist() },
    setDensity(v: Density) { this.density = v; this.apply(); this.persist() },
    setHive(v: Hive) { this.hive = v; this.apply(); this.persist() },
  },
})
