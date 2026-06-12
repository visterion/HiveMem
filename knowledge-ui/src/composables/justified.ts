export interface JustifiedCell<T> { item: T; width: number; height: number }
export interface JustifiedRow<T> { items: JustifiedCell<T>[]; height: number }

/** Aspect ratio (width/height); 1.5 fallback when dimensions are missing/invalid. */
export function aspectOf(width: number | null | undefined, height: number | null | undefined): number {
  if (!width || !height || width <= 0 || height <= 0) return 1.5
  return width / height
}

/**
 * Pack items into Google-Photos-style rows of uniform height.
 * Full rows are scaled to exactly fill containerWidth; the trailing partial row
 * keeps targetRowHeight. Pure — safe to call from a computed.
 */
export function useJustifiedRows<T extends { width: number | null; height: number | null }>(
  items: T[],
  containerWidth: number,
  targetRowHeight = 132,
  gap = 4,
): JustifiedRow<T>[] {
  const rows: JustifiedRow<T>[] = []
  if (containerWidth <= 0 || items.length === 0) return rows

  let current: T[] = []
  let arSum = 0
  const flush = (height: number) => {
    rows.push({
      items: current.map(item => ({ item, width: aspectOf(item.width, item.height) * height, height })),
      height,
    })
    current = []
    arSum = 0
  }

  for (const item of items) {
    current.push(item)
    arSum += aspectOf(item.width, item.height)
    const gaps = (current.length - 1) * gap
    if (arSum * targetRowHeight + gaps >= containerWidth) {
      flush((containerWidth - gaps) / arSum)
    }
  }
  if (current.length) flush(targetRowHeight)
  return rows
}
