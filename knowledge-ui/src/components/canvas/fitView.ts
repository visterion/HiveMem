// Pure zoom-to-fit math for the Hive galaxy (SphereCanvas). Kept dependency-free
// so it can be unit-tested without spinning up Pixi.
//
// Transform semantics this mirrors (see SphereCanvas.vue):
//   world.scale.set(zoom); world.position.set(panX, panY)
// i.e. screen = world * zoom + pan. snapTo() derives its target pan the same
// way: targetPanX = screen.width / 2 - worldX * targetZoom. To center a
// bounding box (cx, cy) in a viewport (vp.w, vp.h):
//   panX = vp.w / 2 - cx * zoom
//   panY = vp.h / 2 - cy * zoom
export function computeFitView(
  b: { minX: number; minY: number; maxX: number; maxY: number },
  vp: { w: number; h: number },
  pad = 40,
): { zoom: number; panX: number; panY: number } {
  const w = Math.max(1, b.maxX - b.minX)
  const h = Math.max(1, b.maxY - b.minY)
  const zoom = Math.min(6, Math.max(0.15, Math.min((vp.w - 2 * pad) / w, (vp.h - 2 * pad) / h)))
  const cx = (b.minX + b.maxX) / 2
  const cy = (b.minY + b.maxY) / 2
  return { zoom, panX: vp.w / 2 - cx * zoom, panY: vp.h / 2 - cy * zoom }
}
