import { test, expect, type Page } from '@playwright/test'

async function gotoMock(page: Page, path: string) {
  await page.addInitScript(() => localStorage.setItem('hivemem_mock', 'true'))
  await page.goto(path)
  await page.waitForTimeout(700)
}

// Regression: the popup used to be anchored right:0 and grew out of the panel.
// - Desktop: the panel (`.panel`) is a fixed-width sidebar sitting next to the main content
//   area. None of its ancestors set `overflow: hidden/auto/scroll` (we checked — the DOM chain
//   from `.sort-pop` up to `<body>` is all `overflow: visible`, and `<body>`/`<html>` span the
//   full viewport), so a plain viewport-bounds check would pass on the buggy code even though
//   the popup visually spills out of the panel into the main content column: boundingBox()
//   reports true layout position, and the pre-fix popup's x stayed positive (just left of the
//   panel's own left edge, not the viewport's). We assert against the panel's own bounding box
//   instead, since that's the container the popup is actually meant to stay inside.
// - Mobile: the panel is a full-width off-canvas drawer, so there's no separate container to
//   escape — the bug there shows up directly as the popup extending past the viewport edge
//   (x goes negative).
test('sort popup stays inside the search panel on desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  await gotoMock(page, '/')
  await page.locator('.toolbar .sort-btn').first().click()
  const box = await page.locator('.sort-pop').boundingBox()
  const panelBox = await page.locator('.panel').boundingBox()
  expect(box).not.toBeNull()
  expect(panelBox).not.toBeNull()
  expect(box!.x).toBeGreaterThanOrEqual(panelBox!.x)
  expect(box!.x + box!.width).toBeLessThanOrEqual(panelBox!.x + panelBox!.width)
})

test('sort popup stays inside the viewport on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await gotoMock(page, '/')
  // The panel is an off-canvas drawer on mobile — open it via the topbar filter button.
  await page.locator('[data-test="mobile-filter"]').click()
  await expect(page.locator('.panel.open')).toBeVisible()
  await page.locator('.toolbar .sort-btn').first().click()
  const box = await page.locator('.sort-pop').boundingBox()
  expect(box).not.toBeNull()
  expect(box!.x).toBeGreaterThanOrEqual(0)
  expect(box!.x + box!.width).toBeLessThanOrEqual(390)
})
