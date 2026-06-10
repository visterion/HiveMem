import { paletteForRealm } from './realmPalette'

export type RealmPriv = 'local' | 'cloud'
export interface RealmMeta { priv: RealmPriv; desc: string }

/**
 * Static front-end metadata for the eight design-system realms. The backend has
 * no per-realm privacy/description; this is a UI config map (real per-realm
 * routing is deferred to SP-G). Descriptions are static German display content.
 */
export const REALM_META: Record<string, RealmMeta> = {
  legal:       { priv: 'local', desc: 'Behörden, Verträge, Gericht' },
  medical:     { priv: 'local', desc: 'Gesundheit, Befunde, Rezepte' },
  private:     { priv: 'local', desc: 'Familie, Persönliches' },
  finance:     { priv: 'local', desc: 'Konten, Steuern, Belege' },
  work:        { priv: 'cloud', desc: 'Projekte, Meetings, Tasks' },
  documents:   { priv: 'cloud', desc: 'Rechnungen, Bescheide, Post' },
  engineering: { priv: 'cloud', desc: 'Architektur, Entscheidungen' },
  codebase:    { priv: 'cloud', desc: 'Repos, Snippets, Notes' },
}

/** SP-A CSS vars (tokens.css) keyed by realm id. */
const VAR_BY_REALM: Record<string, string> = {
  legal: '--r-legal', medical: '--r-medical', private: '--r-private', finance: '--r-finance',
  work: '--r-work', documents: '--r-docs', engineering: '--r-eng', codebase: '--r-code',
}

export function realmMetaFor(id: string): RealmMeta {
  return REALM_META[id] ?? { priv: 'cloud', desc: '' }
}

/** Same hashing as SearchPanel.realmColor — stable per realm name. */
function hashIndex(id: string): number {
  let h = 0
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0
  return h % 12
}

/**
 * Known realms → their SP-A CSS var (e.g. `var(--r-docs)`); unknown realms →
 * a deterministic hashed hex from the realm palette. Returns a CSS color string.
 */
export function realmColorFor(id: string): string {
  const v = VAR_BY_REALM[id]
  if (v) return `var(${v})`
  return paletteForRealm(hashIndex(id)).base
}
