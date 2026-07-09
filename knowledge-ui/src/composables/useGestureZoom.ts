import { ref, computed } from 'vue'

export interface GestureZoomOptions {
  minScale?: number
  maxScale?: number
  initialScale?: number
  doubleTapScale?: number
}

export function useGestureZoom(opts: GestureZoomOptions = {}) {
  const minScale = opts.minScale ?? 1
  const maxScale = opts.maxScale ?? 6
  // Resting "fit" scale the viewer opens at and resets to. Defaults to minScale so callers
  // that only set minScale keep the old behaviour; DocumentViewer sets it to 1 while lowering
  // minScale below 1 so the page can be zoomed out past fit.
  const initialScale = opts.initialScale ?? minScale
  const doubleTapScale = opts.doubleTapScale ?? 2.5

  const scale = ref(initialScale)
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
    // At or below the fit scale there is nothing to pan, so drop any accumulated offset.
    if (clamped <= initialScale) { tx.value = 0; ty.value = 0 }
  }

  function zoomBy(factor: number) { applyScale(scale.value * factor) }
  function setScale(s: number) { applyScale(s) }
  function panBy(dx: number, dy: number) {
    if (scale.value <= initialScale) return
    tx.value += dx
    ty.value += dy
  }
  function reset() { scale.value = initialScale; tx.value = 0; ty.value = 0 }
  function toggleZoom() { applyScale(scale.value > initialScale ? initialScale : doubleTapScale) }

  return { scale, tx, ty, transform, zoomBy, setScale, panBy, reset, toggleZoom, minScale, maxScale, initialScale }
}
