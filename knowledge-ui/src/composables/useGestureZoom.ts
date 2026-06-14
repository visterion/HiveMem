import { ref, computed } from 'vue'

export interface GestureZoomOptions {
  minScale?: number
  maxScale?: number
  doubleTapScale?: number
}

export function useGestureZoom(opts: GestureZoomOptions = {}) {
  const minScale = opts.minScale ?? 1
  const maxScale = opts.maxScale ?? 6
  const doubleTapScale = opts.doubleTapScale ?? 2.5

  const scale = ref(minScale)
  const tx = ref(0)
  const ty = ref(0)

  const transform = computed(
    () => `translate(${tx.value}px, ${ty.value}px) scale(${scale.value})`,
  )

  function clamp(s: number) {
    return Math.min(maxScale, Math.max(minScale, s))
  }

  function applyScale(s: number) {
    const clamped = clamp(s)
    scale.value = clamped
    // Back at minScale there is nothing to pan, so drop any accumulated offset.
    if (clamped === minScale) { tx.value = 0; ty.value = 0 }
  }

  function zoomBy(factor: number) { applyScale(scale.value * factor) }
  function setScale(s: number) { applyScale(s) }
  function panBy(dx: number, dy: number) {
    if (scale.value <= minScale) return
    tx.value += dx
    ty.value += dy
  }
  function reset() { scale.value = minScale; tx.value = 0; ty.value = 0 }
  function toggleZoom() { applyScale(scale.value > minScale ? minScale : doubleTapScale) }

  return { scale, tx, ty, transform, zoomBy, setScale, panBy, reset, toggleZoom, minScale, maxScale }
}
