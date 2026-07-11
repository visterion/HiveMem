export interface DatedItem {
  taken_at: string | null
  created_at: string | null
}
export interface PhotoGroup<T> { key: string; items: T[] }

/** Effective capture date = taken_at, else created_at. */
function effectiveDate(item: DatedItem): string | null {
  return item.taken_at ?? item.created_at ?? null
}

/**
 * Bucket key for a date relative to `now`: today | week | month | YYYY-MM.
 *
 * Uses LOCAL calendar-day components throughout (not UTC): comparing a local
 * "today" against a UTC calendar day put early-morning/late-evening photos
 * (anywhere west/east of UTC) in the wrong bucket — e.g. a photo taken at
 * 23:30 local time (02:30 UTC the next day) fell out of "Today" the moment
 * the UTC date rolled over, hours before local midnight.
 */
export function bucketKeyFor(dateIso: string | null, now: Date): string {
  if (!dateIso) return 'older'
  const d = new Date(dateIso)
  if (Number.isNaN(d.getTime())) return 'older'
  const sameDay = d.getFullYear() === now.getFullYear()
    && d.getMonth() === now.getMonth() && d.getDate() === now.getDate()
  if (sameDay) return 'today'
  const days = (now.getTime() - d.getTime()) / 86_400_000
  if (days >= 0 && days < 7) return 'week'
  if (d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()) return 'month'
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

/**
 * Group items by bucket, preserving input order (callers pass newest-first), with
 * groups in first-appearance order (also newest-first).
 */
export function groupPhotos<T extends DatedItem>(items: T[], now: Date): PhotoGroup<T>[] {
  const order: string[] = []
  const byKey = new Map<string, T[]>()
  for (const item of items) {
    const key = bucketKeyFor(effectiveDate(item), now)
    if (!byKey.has(key)) { byKey.set(key, []); order.push(key) }
    byKey.get(key)!.push(item)
  }
  return order.map(key => ({ key, items: byKey.get(key)! }))
}
