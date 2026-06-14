// HiveMem cells have no `title` field — neither `search` nor `get_cell` returns one.
// A human-readable label is derived from summary, then a snippet of the (OCR/parsed)
// content, then topic, finally a short id. This keeps search results and panels
// identifiable for every cell, including bare scanned documents whose summary is null.

export interface LabelableCell {
  id: string
  summary?: string | null
  content?: string | null
  topic?: string | null
}

const MAX_LABEL = 80

export function contentSnippet(content?: string | null): string {
  if (!content) return ''
  // Strip page markers like "[page=1]" injected by the reassembly OCR, collapse
  // whitespace, and take the first meaningful slice.
  const cleaned = content
    .replace(/\[page=\d+\]/gi, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return cleaned.slice(0, MAX_LABEL)
}

export function cellLabel(c: LabelableCell): string {
  const summary = c.summary?.trim()
  if (summary) return summary.length > MAX_LABEL ? summary.slice(0, MAX_LABEL) + '…' : summary
  const snippet = contentSnippet(c.content)
  if (snippet) return snippet
  const topic = c.topic?.trim()
  if (topic) return topic
  return '#' + c.id.slice(0, 8)
}

// Short display name for a document: the LLM-generated title lives in `topic`. Fall back to the
// (truncated) summary/snippet label when a document has not been titled yet.
export function docName(c: LabelableCell): string {
  const topic = c.topic?.trim()
  if (topic) return topic
  return cellLabel(c)
}
