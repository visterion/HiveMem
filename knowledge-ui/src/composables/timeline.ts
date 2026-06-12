import type { Cell } from '../api/types'

/** Cells sorted ascending by valid_from (stable; null/empty valid_from sort first). */
export function sortByValidFrom(cells: Cell[]): Cell[] {
  return [...cells].sort((a, b) => (a.valid_from || '').localeCompare(b.valid_from || ''))
}
