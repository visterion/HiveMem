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
    z.setScale(3)
    z.panBy(5, 5); z.panBy(5, -2)
    expect(z.tx.value).toBe(10); expect(z.ty.value).toBe(3)
  })
})
