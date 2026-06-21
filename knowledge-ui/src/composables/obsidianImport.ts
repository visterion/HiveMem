// Client-side Obsidian-vault parsing for SP4 bulk import. Pure, dependency-free so it is
// unit-testable; the .zip extraction (jszip) and the add_cell/add_tunnel calls live in the
// dialog / store. Each note becomes a cell; [[wiki-links]] become tunnels, #tags become
// tags, and frontmatter `created` becomes valid_from.

export interface ParsedNote {
  title: string
  realm?: string
  signal?: string
  content: string
  tags: string[]
  links: string[]
  validFrom?: string
}

export interface ImportPlan {
  notes: ParsedNote[]
  missingTargets: string[]
  stats: { noteCount: number; tagCount: number; linkCount: number }
}

const FRONTMATTER_RE = /^---\n([\s\S]*?)\n---\n?/
const WIKILINK_RE = /\[\[([^\]]+)\]\]/g
const INLINE_TAG_RE = /(?:^|\s)#([A-Za-z0-9_][\w/-]*)/g
const FM_LINE_RE = /^([A-Za-z0-9_-]+):\s*(.*)$/
const FM_LIST_ITEM_RE = /^\s*-\s+(.*)$/

function baseName(path: string): string {
  const file = path.split(/[\\/]/).pop() ?? path
  return file.replace(/\.md$/i, '')
}

function parseTagList(value: string): string[] {
  const inner = value.trim().replace(/^\[/, '').replace(/\]$/, '')
  return inner.split(',').map(s => s.trim().replace(/^["']|["']$/g, '')).filter(Boolean)
}

interface Frontmatter { map: Record<string, string>; tags: string[] }

function parseFrontmatter(block: string): Frontmatter {
  const map: Record<string, string> = {}
  const tags: string[] = []
  const lines = block.split('\n')
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(FM_LINE_RE)
    if (!m) continue
    const key = m[1].toLowerCase()
    const val = m[2].trim()
    if (key === 'tags') {
      if (val) {
        tags.push(...parseTagList(val))
      } else {
        // list form: consume following "- item" lines
        for (let j = i + 1; j < lines.length; j++) {
          const item = lines[j].match(FM_LIST_ITEM_RE)
          if (!item) break
          tags.push(item[1].trim().replace(/^["']|["']$/g, ''))
          i = j
        }
      }
    } else {
      map[key] = val.replace(/^["']|["']$/g, '')
    }
  }
  return { map, tags }
}

export function parseNote(name: string, raw: string): ParsedNote {
  const title = baseName(name)
  let body = raw
  const fmMatch = raw.match(FRONTMATTER_RE)
  let fmTags: string[] = []
  let map: Record<string, string> = {}
  if (fmMatch) {
    const fm = parseFrontmatter(fmMatch[1])
    map = fm.map
    fmTags = fm.tags
    body = raw.slice(fmMatch[0].length)
  }

  const tags: string[] = []
  const seen = new Set<string>()
  const pushTag = (t: string) => { if (t && !seen.has(t)) { seen.add(t); tags.push(t) } }
  fmTags.forEach(pushTag)
  for (const m of body.matchAll(INLINE_TAG_RE)) pushTag(m[1])

  const links: string[] = []
  const seenLinks = new Set<string>()
  for (const m of body.matchAll(WIKILINK_RE)) {
    const target = m[1].split('|')[0].split('#')[0].trim()
    if (target && !seenLinks.has(target)) { seenLinks.add(target); links.push(target) }
  }

  return {
    title,
    realm: map.realm || undefined,
    signal: map.signal || undefined,
    content: body,
    tags,
    links,
    validFrom: map.created || map.date || undefined,
  }
}

export function buildPlan(files: { name: string; text: string }[]): ImportPlan {
  const notes = files
    .filter(f => /\.md$/i.test(f.name))
    .map(f => parseNote(f.name, f.text))
  const titles = new Set(notes.map(n => n.title))
  const allTags = new Set<string>()
  const allLinks = new Set<string>()
  const missing = new Set<string>()
  for (const n of notes) {
    n.tags.forEach(t => allTags.add(t))
    for (const link of n.links) {
      allLinks.add(`${n.title}→${link}`)
      if (!titles.has(link)) missing.add(link)
    }
  }
  return {
    notes,
    missingTargets: [...missing],
    stats: { noteCount: notes.length, tagCount: allTags.size, linkCount: allLinks.size },
  }
}
