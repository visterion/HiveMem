import { describe, it, expect } from 'vitest'
import { computeFitView } from '../../src/components/canvas/fitView'

// Transform contract (derived from SphereCanvas.vue, not guessed): the world
// container is rendered as `screen = world * zoom + pan`
// (`world.scale.set(zoom); world.position.set(panX, panY)`), and snapTo()
// derives its target pan the same way: `screen.width/2 - worldX * targetZoom`.
// So centering a bounding box (cx, cy) in a viewport requires
//   panX = vp.w / 2 - cx * zoom
//   panY = vp.h / 2 - cy * zoom
// — not `panX = -cx * zoom` (that would only be correct if the viewport
// origin, not its center, were the anchor).
describe('computeFitView', () => {
  it('fits a wide world into a narrow viewport, centering it in the viewport', () => {
    const v = computeFitView({ minX: -800, minY: -400, maxX: 800, maxY: 400 }, { w: 390, h: 700 }, 40)
    expect(v.zoom).toBeCloseTo((390 - 80) / 1600, 3)
    // bounds are centered on world (0,0), so pan must land it on the viewport center.
    expect(v.panX).toBeCloseTo(390 / 2, 3)
    expect(v.panY).toBeCloseTo(700 / 2, 3)
  })

  it('offsets pan to center an off-origin bounding box in the viewport', () => {
    const v = computeFitView({ minX: 100, minY: 200, maxX: 300, maxY: 400 }, { w: 1000, h: 1000 }, 0)
    // cx=200, cy=300; zoom = min(1000/200, 1000/200) clamped = 5 (before clamp check below)
    const expectedZoom = Math.min(6, Math.max(0.15, Math.min(1000 / 200, 1000 / 200)))
    expect(v.zoom).toBeCloseTo(expectedZoom, 5)
    expect(v.panX).toBeCloseTo(1000 / 2 - 200 * expectedZoom, 3)
    expect(v.panY).toBeCloseTo(1000 / 2 - 300 * expectedZoom, 3)
  })

  it('clamps zoom to [0.15, 6]', () => {
    expect(computeFitView({ minX: -10, minY: -10, maxX: 10, maxY: 10 }, { w: 1000, h: 1000 }).zoom).toBeLessThanOrEqual(6)
  })

  it('clamps zoom to the floor for a huge world in a tiny viewport', () => {
    expect(
      computeFitView({ minX: -100000, minY: -100000, maxX: 100000, maxY: 100000 }, { w: 100, h: 100 }).zoom,
    ).toBeGreaterThanOrEqual(0.15)
  })
})
