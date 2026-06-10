import type { GraphLink } from './types'

/** Global zoom scale at/above which every node label is drawn. */
export const LABEL_ZOOM = 1.6

/** Normalize a force-graph link end (string id, or node object after the sim runs). */
export function idOf(end: unknown): string {
  if (end == null) return ''
  if (typeof end === 'object') return String((end as { id?: unknown }).id ?? '')
  return String(end)
}

/** Place each unique realm evenly on a ring → per-realm cluster anchor points. */
export function realmClusterCenters(
  realms: string[],
  radius = 320,
): Record<string, { x: number; y: number }> {
  const unique: string[] = []
  for (const r of realms) if (!unique.includes(r)) unique.push(r)
  const centers: Record<string, { x: number; y: number }> = {}
  const n = unique.length
  if (n === 0) return centers
  if (n === 1) {
    centers[unique[0]] = { x: 0, y: 0 }
    return centers
  }
  unique.forEach((r, i) => {
    const angle = (i / n) * 2 * Math.PI
    centers[r] = { x: Math.cos(angle) * radius, y: Math.sin(angle) * radius }
  })
  return centers
}

/** The active node plus its directly linked neighbors (empty when nothing active). */
export function neighborIds(activeId: string | null, links: GraphLink[]): Set<string> {
  const ids = new Set<string>()
  if (!activeId) return ids
  ids.add(activeId)
  for (const link of links) {
    const s = idOf(link.source)
    const t = idOf(link.target)
    if (s === activeId) ids.add(t)
    else if (t === activeId) ids.add(s)
  }
  return ids
}

/** Show a label when zoomed in past the threshold, or for highlighted nodes while something is active. */
export function shouldShowLabel(
  nodeId: string,
  opts: { globalScale: number; highlightIds: Set<string>; hasActive: boolean },
): boolean {
  if (opts.globalScale >= LABEL_ZOOM) return true
  return opts.hasActive && opts.highlightIds.has(nodeId)
}
