import { test, expect, type Page } from '@playwright/test'

async function gotoMockLight(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('hivemem_mock', 'true')
    // prefs store persists the whole prefs object as JSON under 'hivemem_prefs'
    // (see src/stores/prefs.ts); light theme is what exposes the hardcoded dark hex.
    localStorage.setItem(
      'hivemem_prefs',
      JSON.stringify({ theme: 'light', accent: '#F4B740', density: 'regular', hive: 'spürbar' }),
    )
  })
  await page.goto('/')
  await page.waitForTimeout(700)
}

test('reader background follows the light theme', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  await gotoMockLight(page)
  // Open a document via the scans route, where cards reliably have an attachment (the mock's
  // search cells don't). Clicking a scan card opens the document reader (Reader.vue), whose
  // shell background in the light theme is what this test checks.
  await page.goto('/scans')
  await page.locator('[class*="card"]').first().waitFor({ timeout: 6000 })
  await page.locator('[class*="card"]').first().click()
  await expect(page.locator('.reader-shell')).toBeVisible({ timeout: 4000 })

  const bg = await page.locator('.reader-shell').evaluate(
    (el) => getComputedStyle(el).backgroundColor,
  )
  // The dark hardcode was #0a0a14 = rgb(10, 10, 20). In the light theme the reader must not be dark.
  expect(bg).not.toBe('rgb(10, 10, 20)')
  const [r, g, b] = bg.match(/\d+/g)!.map(Number)
  expect((r + g + b) / 3).toBeGreaterThan(128) // a light surface
})
