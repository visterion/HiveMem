import { createElement, type ReactElement, type MutableRefObject } from 'react'
import ForceGraph2D, {
  type ForceGraphProps,
  type ForceGraphMethods,
  type LinkObject as ForceGraphLinkObject,
  type NodeObject as ForceGraphNodeObject
} from 'react-force-graph-2d'
import type { GraphLink, GraphNode } from '../../graph/types'
import { neighborIds, shouldShowLabel, idOf } from '../../graph/clusterForce'

type ForceGraphNode = ForceGraphNodeObject<GraphNode>
type ForceGraphLink = ForceGraphLinkObject<GraphNode, GraphLink>
type ForceGraphComponent = (
  props: ForceGraphProps<GraphNode, GraphLink> & {
    ref?: MutableRefObject<ForceGraphMethods<GraphNode, GraphLink> | undefined>
  }
) => ReactElement

const ForceGraph2DComponent = ForceGraph2D as unknown as ForceGraphComponent

const DIM = 0.15
const LABEL_COLOR = '#C5CBD6'
const DIM_LINK = 'rgba(70,214,224,0.08)'

export function ForceGraphRoot(props: {
  graph: { nodes: GraphNode[]; links: GraphLink[] }
  width: number
  height: number
  focusedId: string | null
  hoveredId: string | null
  onNodeHover: (id: string | null) => void
  onNodeClick: (id: string) => void
  forceGraphRef?: MutableRefObject<ForceGraphMethods<GraphNode, GraphLink> | undefined>
}) {
  const activeId = props.focusedId ?? props.hoveredId
  const hasActive = activeId != null
  const highlightIds = neighborIds(activeId, props.graph.links)

  return createElement(ForceGraph2DComponent, {
    ref: props.forceGraphRef,
    // Pass the caller-owned object through untouched: its identity changes only
    // when the actual node/link data changes, which is what keeps react-kapsule
    // from reheating the d3 simulation on hover/focus/resize renders.
    graphData: props.graph,
    width: props.width,
    height: props.height,
    nodeLabel: 'label',
    nodeColor: 'color',
    nodeCanvasObject: (node: ForceGraphNode, ctx: CanvasRenderingContext2D, globalScale: number) => {
      const id = typeof node.id === 'string' ? node.id : String(node.id)
      const isFocused = id === props.focusedId
      const isHovered = id === props.hoveredId
      const dim = hasActive && !highlightIds.has(id) ? DIM : 1
      const radius = (node.val ?? 1) + (isFocused ? 4 : isHovered ? 2 : 0)
      const color = node.color ?? '#888888'

      // shadowBlur is the most expensive Canvas2D op — reserve the glow for nodes
      // the user is actually looking at instead of paying it for every node per frame.
      const glowing = isFocused || isHovered || (hasActive && highlightIds.has(id))
      ctx.save()
      ctx.globalAlpha = dim
      if (glowing) {
        ctx.shadowColor = color
        ctx.shadowBlur = radius * 1.6
      }
      ctx.beginPath()
      ctx.arc(node.x ?? 0, node.y ?? 0, radius, 0, 2 * Math.PI)
      ctx.fillStyle = color
      ctx.fill()
      ctx.shadowBlur = 0

      if (shouldShowLabel(id, { globalScale, highlightIds, hasActive })) {
        const fontSize = 12 / globalScale
        ctx.font = `${fontSize}px sans-serif`
        ctx.textAlign = 'center'
        ctx.textBaseline = 'top'
        ctx.fillStyle = LABEL_COLOR
        ctx.fillText(node.label ?? '', node.x ?? 0, (node.y ?? 0) + radius + 1)
      }
      ctx.restore()
    },
    linkColor: (link: ForceGraphLink) => {
      if (!hasActive) return link.color
      const incident = highlightIds.has(idOf(link.source)) && highlightIds.has(idOf(link.target))
      return incident ? link.color : DIM_LINK
    },
    linkWidth: (link: ForceGraphLink) => {
      if (!hasActive) return 1
      const incident = highlightIds.has(idOf(link.source)) && highlightIds.has(idOf(link.target))
      return incident ? 2 : 1
    },
    onNodeHover: (node: ForceGraphNode | null) =>
      props.onNodeHover(typeof node?.id === 'string' ? node.id : null),
    onNodeClick: (node: ForceGraphNode) => {
      if (typeof node.id === 'string') props.onNodeClick(node.id)
    }
  })
}
