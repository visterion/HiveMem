import { describe, expect, it } from 'vitest'
import { i18n } from '../../src/i18n'

describe('scans i18n keys', () => {
  const keys = [
    'scans.savedViews', 'scans.allDocs', 'scans.items', 'scans.searchDocs',
    'scans.docType', 'scans.correspondent', 'scans.year', 'scans.date', 'scans.amount',
    'scans.selected', 'scans.bulkTag', 'scans.bulkRealm', 'scans.bulkApprove', 'scans.bulkExport',
    'scans.clearFilter', 'scans.extracted', 'scans.subtype', 'scans.archiveNo',
    'scans.confidence', 'scans.states', 'scans.ocrText', 'scans.similar', 'scans.sameCorr',
    'scans.page', 'scans.of', 'scans.approve', 'scans.editMeta', 'scans.openOriginal',
    'scans.noResults', 'scans.noResultsSub',
    'scans.newestFirst', 'scans.oldestFirst', 'scans.titleAZ', 'scans.sortBy',
    'scans.pending', 'scans.committed',
  ]
  it('resolves all keys in de and en', () => {
    for (const locale of ['de', 'en'] as const) {
      i18n.global.locale.value = locale
      for (const k of keys) expect(i18n.global.t(k), `${locale}:${k}`).not.toBe(k)
    }
  })
})
