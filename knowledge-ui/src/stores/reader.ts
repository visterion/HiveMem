import { defineStore } from 'pinia'

export type ReaderTab = 'markdown' | string

export const useReaderStore = defineStore('reader', {
  state: () => ({
    open: false,
    cellId: null as string | null,
    activeTab: 'markdown' as ReaderTab
  }),
  actions: {
    openReader(cellId: string) { this.cellId = cellId; this.open = true; this.activeTab = 'markdown' },
    close() { this.open = false }
  }
})
