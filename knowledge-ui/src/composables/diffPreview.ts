import { diffLines } from 'diff'

export type DiffPartType = 'add' | 'del' | 'context'
export interface DiffPart { type: DiffPartType; value: string }
export interface LineDiff {
  parts: DiffPart[]
  added: number
  removed: number
  changed: boolean
}

// Line-level diff for the pre-save preview. `added`/`removed` count changed lines so the
// UI can show e.g. "+2 −1" before the user commits a new revision.
export function computeLineDiff(oldText: string, newText: string): LineDiff {
  const parts: DiffPart[] = []
  let added = 0
  let removed = 0
  for (const change of diffLines(oldText ?? '', newText ?? '')) {
    const lineCount = change.count ?? change.value.split('\n').length - (change.value.endsWith('\n') ? 1 : 0)
    if (change.added) {
      parts.push({ type: 'add', value: change.value })
      added += lineCount
    } else if (change.removed) {
      parts.push({ type: 'del', value: change.value })
      removed += lineCount
    } else {
      parts.push({ type: 'context', value: change.value })
    }
  }
  return { parts, added, removed, changed: added > 0 || removed > 0 }
}
