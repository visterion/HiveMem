import { describe, it, expect } from 'vitest'
import { useGestureZoom } from '../../src/composables/useGestureZoom'

describe('useGestureZoom', () => {
  it('starts at minScale with no translate and an identity transform', () => {
    const z = useGestureZoom()
    expect(z.scale.value).toBe(1)
    expect(z.tx.value).toBe(0)
    expect(z.ty.value).toBe(0)
    expect(z.transform.value).toBe('translate(0px, 0px) scale(1)')
  })

  it('zoomBy multiplies and clamps to [minScale, maxScale]', () => {
    const z = useGestureZoom({ minScale: 1, maxScale: 4 })
    z.zoomBy(2);   expect(z.scale.value).toBe(2)
    z.zoomBy(10);  expect(z.scale.value).toBe(4)   // clamped to max
    z.zoomBy(0.01); expect(z.scale.value).toBe(1)  // clamped to min
  })

  it('setScale clamps and resets translate when back at minScale', () => {
    const z = useGestureZoom({ minScale: 1, maxScale: 4 })
    z.setScale(3); z.panBy(20, -10)
    expect(z.tx.value).toBe(20); expect(z.ty.value).toBe(-10)
    z.setScale(1) // back to min -> translate cleared
    expect(z.tx.value).toBe(0); expect(z.ty.value).toBe(0)
  })

  it('toggleZoom toggles between minScale and doubleTapScale', () => {
    const z = useGestureZoom({ minScale: 1, maxScale: 6, doubleTapScale: 2.5 })
    z.toggleZoom(); expect(z.scale.value).toBe(2.5)
    z.toggleZoom(); expect(z.scale.value).toBe(1)
  })

  it('panBy accumulates translate only while zoomed', () => {
    const z = useGestureZoom()
    z.panBy(99, 99) // no-op while at minScale
    expect(z.tx.value).toBe(0); expect(z.ty.value).toBe(0)
    z.setScale(3)
    z.panBy(5, 5); z.panBy(5, -2)
    expect(z.tx.value).toBe(10); expect(z.ty.value).toBe(3)
  })

  it('opens at initialScale (fit) while allowing zoom-out down to minScale', () => {
    const z = useGestureZoom({ minScale: 0.25, initialScale: 1, maxScale: 6 })
    expect(z.scale.value).toBe(1)            // fit, not minScale
    z.zoomBy(0.8); expect(z.scale.value).toBeCloseTo(0.8) // below 100%
    z.zoomBy(0.1); expect(z.scale.value).toBe(0.25)       // clamped to minScale
  })

  it('reset and toggleZoom return to initialScale, not minScale', () => {
    const z = useGestureZoom({ minScale: 0.25, initialScale: 1, maxScale: 6, doubleTapScale: 2.5 })
    z.setScale(3)
    z.reset(); expect(z.scale.value).toBe(1)
    z.toggleZoom(); expect(z.scale.value).toBe(2.5)
    z.toggleZoom(); expect(z.scale.value).toBe(1)
  })

  it('does not pan while at or below initialScale', () => {
    const z = useGestureZoom({ minScale: 0.25, initialScale: 1 })
    z.setScale(0.5)
    z.panBy(10, 10)
    expect(z.tx.value).toBe(0); expect(z.ty.value).toBe(0)
  })
})
