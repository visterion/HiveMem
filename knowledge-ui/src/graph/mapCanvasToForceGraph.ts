import type { Cell, Tunnel } from '../api/types'
import type { GraphLink, GraphNode } from './types'
import { colorForRelation, colorForRealm } from './colors'

export function mapCanvasToForceGraph(input: { cells: Cell[]; tunnels: Tunnel[] }) {
  const nodeIds = new Set(input.cells.map(cell => cell.id))

  // Keep only tunnels whose both ends are present, then count degree per node.
  const links: GraphLink[] = input.tunnels
    .filter(tunnel => nodeIds.has(tunnel.from_cell) && nodeIds.has(tunnel.to_cell))
    .map(tunnel => ({
      id: tunnel.id,
      source: tunnel.from_cell,
      target: tunnel.to_cell,
      relation: tunnel.relation,
      color: colorForRelation(tunnel.relation)
    }))

  const degree = new Map<string, number>()
  for (const link of links) {
    degree.set(link.source, (degree.get(link.source) ?? 0) + 1)
    degree.set(link.target, (degree.get(link.target) ?? 0) + 1)
  }

  const nodes: GraphNode[] = input.cells.map(cell => {
    const deg = degree.get(cell.id) ?? 0
    const importance = cell.importance ?? 1
    // Obsidian-like: size dominated by connection count, importance a minor term,
    // floored so isolated nodes stay visible.
    const val = Math.max(2, Math.sqrt(deg) * 3 + importance * 0.5)
    return {
      id: cell.id,
      label: cell.title,
      realm: cell.realm,
      signal: cell.signal ?? null,
      topic: cell.topic ?? null,
      importance: cell.importance,
      val,
      color: colorForRealm(cell.realm)
    }
  })

  return { nodes, links }
}
