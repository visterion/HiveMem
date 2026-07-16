import { describe, expect, it } from 'vitest'
import { i18n } from '../../src/i18n'

describe('shell i18n keys', () => {
  const keys = [
    'nav.search','nav.hive','nav.graph','nav.realms','nav.photos','nav.scans',
    'nav.timemachine','nav.queen','nav.settings','nav.upload',
    'tweaks.appearance','tweaks.layout','tweaks.theme','tweaks.language','tweaks.accent','tweaks.density','tweaks.hive',
    'common.comingSoon',
    'upload.title','pwa.updateReady','pwa.reload',
  ]
  it('resolves all shell keys in de and en (no key fallthrough)', () => {
    for (const locale of ['de','en'] as const) {
      i18n.global.locale.value = locale
      for (const k of keys) {
        expect(i18n.global.t(k), `${locale}:${k}`).not.toBe(k)
      }
    }
  })
})
