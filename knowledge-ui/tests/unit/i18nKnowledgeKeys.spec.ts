import { describe, expect, it } from 'vitest'
import { i18n } from '../../src/i18n'

describe('knowledge/inspector i18n keys', () => {
  const keys = [
    'inspector.cell','inspector.signals','inspector.metadata','inspector.source','inspector.openDoc','inspector.showGraph',
    'inspector.sig_semantic','inspector.sig_keyword','inspector.sig_recency','inspector.sig_importance','inspector.sig_popularity','inspector.sig_graph_proximity',
    'inspector.type','inspector.importance','inspector.validFrom','inspector.validUntil','inspector.present',
    'knowledge.selectCell','knowledge.selectCellSub','knowledge.results','knowledge.allTypes',
  ]
  it('resolves all keys in de and en', () => {
    for (const locale of ['de','en'] as const) {
      i18n.global.locale.value = locale
      for (const k of keys) expect(i18n.global.t(k), `${locale}:${k}`).not.toBe(k)
    }
  })
})
