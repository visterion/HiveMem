import { paletteForRealm } from './realmPalette'

export type RealmPriv = 'local' | 'cloud'
export interface RealmMeta { priv: RealmPriv; desc: string }

/**
 * Shared sentinel for "no realm" (unclassified inbox cells). Mirrors the backend's
 * own `where.realm="none"` sentinel (see the search API's DocumentRow mapping) — this
 * is the single definition every call site should import instead of repeating the
 * literal string.
 */
export const NO_REALM = 'none'

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

export function realmMetaFor(id: string | null | undefined): RealmMeta {
  return (id ? REALM_META[id] : undefined) ?? { priv: 'cloud', desc: '' }
}

/** Colour for cells that have no realm yet (unclassified inbox cells — a legitimate state). */
const NO_REALM_COLOR = 'var(--text-2)'

/**
 * Stable palette index for a realm name. The single copy of this hash — `graph/colors.ts` imports it
 * for its canvas-hex resolver, which cannot use `realmColorFor` because ctx.fillStyle cannot resolve
 * CSS custom properties. Callers must pass a non-empty id; see realmColorFor for the null gate.
 */
export function hashIndex(id: string): number {
  let h = 0
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0
  return h % 12
}

/**
 * Known realms → their SP-A CSS var (e.g. `var(--r-docs)`); unknown realms → a deterministic
 * hashed hex from the realm palette; no realm → a neutral token. Returns a CSS color string.
 *
 * The backend legitimately returns realm=null for unclassified inbox cells (the search API even
 * has a `realm="none"` sentinel for them), so null is a normal input here, not a data defect.
 */
export function realmColorFor(id: string | null | undefined): string {
  if (!id) return NO_REALM_COLOR
  const v = VAR_BY_REALM[id]
  if (v) return `var(${v})`
  return paletteForRealm(hashIndex(id)).base
}
