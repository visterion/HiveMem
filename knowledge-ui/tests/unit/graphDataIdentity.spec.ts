import { describe, expect, it } from 'vitest'
import type { GraphLink, GraphNode } from '../../src/graph/types'
import { ForceGraphRoot } from '../../src/components/graph/ForceGraphRoot'

const noop = () => {}

function render(graph: { nodes: GraphNode[]; links: GraphLink[] }, hoveredId: string | null) {
  return ForceGraphRoot({
    graph,
    width: 640,
    height: 480,
    focusedId: null,
    hoveredId,
    onNodeHover: noop,
    onNodeClick: noop
  })
}

describe('ForceGraphRoot graphData identity', () => {
  it('keeps graphData reference-identical across hover-only re-renders', () => {
    const graph = {
      nodes: [{ id: 'a' } as GraphNode],
      links: [] as GraphLink[]
    }
    const idle = render(graph, null)
    const hovered = render(graph, 'a')
    // Same input graph object => same graphData object. Anything else makes
    // react-kapsule treat every hover render as a data change and reheat d3.
    expect(idle.props.graphData).toBe(graph)
    expect(hovered.props.graphData).toBe(graph)
  })
})

function paintNode(el: ReturnType<typeof render>, node: Record<string, unknown>) {
  const sets: Record<string, unknown[]> = {}
  const ctx = new Proxy({} as Record<string, unknown>, {
    get(t, p) {
      if (typeof t[p as string] !== 'function' && !(p in t)) t[p as string] = () => {}
      return t[p as string]
    },
    set(t, p, v) {
      ;(sets[String(p)] ??= []).push(v)
      t[p as string] = v
      return true
    }
  }) as unknown as CanvasRenderingContext2D
  el.props.nodeCanvasObject(node, ctx, 1)
  return sets
}

describe('ForceGraphRoot node glow', () => {
  const graph = { nodes: [{ id: 'a' } as GraphNode], links: [] as GraphLink[] }

  it('does not apply shadowBlur to idle nodes', () => {
    const sets = paintNode(render(graph, null), { id: 'a', x: 0, y: 0, val: 1 })
    const blurs = (sets.shadowBlur ?? []).filter(v => typeof v === 'number' && v > 0)
    expect(blurs).toEqual([])
  })

  it('applies shadowBlur to the hovered node', () => {
    const sets = paintNode(render(graph, 'a'), { id: 'a', x: 0, y: 0, val: 1 })
    const blurs = (sets.shadowBlur ?? []).filter(v => typeof v === 'number' && v > 0)
    expect(blurs.length).toBeGreaterThan(0)
  })
})
