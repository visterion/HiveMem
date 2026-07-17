import { test, expect, type Page } from '@playwright/test'

async function gotoMock(page: Page, path: string) {
  await page.addInitScript(() => localStorage.setItem('hivemem_mock', 'true'))
  await page.goto(path)
  await page.waitForTimeout(700)
}

// Regression: the popup used to be anchored right:0 and grew out of the panel (clipped on
// desktop, off-viewport on mobile at left=-35).
for (const vp of [{ name: 'desktop', width: 1280, height: 800 }, { name: 'mobile', width: 390, height: 844 }]) {
  test(`sort popup stays inside the viewport on ${vp.name}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await gotoMock(page, '/')
    if (vp.name === 'mobile') {
      // The panel is an off-canvas drawer on mobile — open it via the topbar filter button.
      await page.locator('[data-test="mobile-filter"]').click()
      await expect(page.locator('.panel.open')).toBeVisible()
    }
    await page.locator('.toolbar .sort-btn').first().click()
    const box = await page.locator('.sort-pop').boundingBox()
    expect(box).not.toBeNull()
    expect(box!.x).toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width).toBeLessThanOrEqual(vp.width)
  })
}
