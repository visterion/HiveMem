import type { Relation } from '../api/types'
import { paletteForRealm } from '../composables/realmPalette'
import { hashIndex } from '../composables/realmMeta'

// Canvas (ctx.fillStyle) cannot read CSS custom properties, so these mirror the
// theme-stable token hex values from styles/tokens.css (--r-* and --cyber/--danger
// are defined once, not overridden per theme). Keep in sync with tokens.css.
const REALM_HEX: Record<string, string> = {
  legal: '#F4B740',
  medical: '#3FB6A8',
  private: '#E8638C',
  work: '#6E78F0',
  documents: '#4FB3F0',
  engineering: '#9BCB3C',
  codebase: '#A781F2',
  finance: '#46C08A',
}

const RELATION_HEX: Record<Relation, string> = {
  related_to: '#46D6E0', // --cyber
  builds_on: '#5BD6CF',
  refines: '#6FB6E8',
  contradicts: '#F0676B', // --danger (kept semantic)
}

export function colorForRealm(name: string | null | undefined): string {
  if (!name) return paletteForRealm(0).base
  return REALM_HEX[name] ?? paletteForRealm(hashIndex(name)).base
}

export function colorForRelation(relation: string): string {
  return RELATION_HEX[relation as Relation] ?? '#46D6E0'
}
