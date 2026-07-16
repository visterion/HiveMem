import { defineStore } from 'pinia'

export type PanelId = null | 'search' | 'realms' | 'reading' | 'stats' | 'history' | 'settings'
export type SizeMetric = 'cell_count' | 'content_volume' | 'importance' | 'popularity'

export const useUiStore = defineStore('ui', {
  state: () => ({
    activePanel: null as PanelId,
    sizeMetric: 'cell_count' as SizeMetric,
    theme: 'dark' as 'dark' | 'light',
    searchQuery: '',
    showLoginDialog: false,
    toast: null as null | { kind: 'info' | 'success' | 'error'; text: string },
    mobileDrawerOpen: false,
    swUpdateReady: false,
    swUpdater: null as (() => void) | null,
  }),
  actions: {
    pushToast(kind: 'info' | 'success' | 'error', text: string) {
      this.toast = { kind, text }
    },
    setDrawer(open: boolean) { this.mobileDrawerOpen = open },
    toggleDrawer() { this.mobileDrawerOpen = !this.mobileDrawerOpen },
    setSwUpdate(fn: () => void) { this.swUpdater = fn; this.swUpdateReady = true },
    applySwUpdate() { this.swUpdater?.(); this.swUpdateReady = false },
  }
})
