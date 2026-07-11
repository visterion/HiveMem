import { defineStore } from 'pinia'

// 'info' is the layered overview tab (summary / key points / insight / text);
// any other value is an attachment id selecting the document viewer.
export type ReaderTab = 'info' | string

export const useReaderStore = defineStore('reader', {
  state: () => ({
    open: false,
    cellId: null as string | null,
    activeTab: 'info' as ReaderTab,
    // Fallback page count for the info tab when the document-list row lacks
    // page_count (e.g. a search-mode row). Set by DocumentViewer once a PDF
    // loads; reset whenever a different document is opened or the reader closes.
    pageCount: null as number | null
  }),
  actions: {
    // initialTab lets callers land on the document itself (an attachment id) rather
    // than the overview — e.g. opening a scan jumps straight to the scanned page.
    openReader(cellId: string, initialTab: ReaderTab = 'info') {
      this.cellId = cellId; this.open = true; this.activeTab = initialTab; this.pageCount = null
    },
    close() { this.open = false; this.pageCount = null }
  }
})
