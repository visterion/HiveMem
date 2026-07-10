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
