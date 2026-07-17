import { test, expect, type Page } from '@playwright/test'

async function gotoMock(page: Page, path: string) {
  await page.addInitScript(() => localStorage.setItem('hivemem_mock', 'true'))
  await page.goto(path)
  await page.waitForTimeout(700)
}

// `width` does not discriminate here: Chromium reports the declared `3px` from
// `.rail-btn.active::before` regardless of whether `content: none` suppresses the box.
// `content` does discriminate — 'none' vs the quoted empty string — so assert on that.
const activeBarContent = (page: Page) =>
  page.locator('.rail-btn.active').first().evaluate(
    (el) => getComputedStyle(el, '::before').content,
  )

test('active-item bar is hidden on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await gotoMock(page, '/')
  expect(await activeBarContent(page)).toBe('none')
})

test('active-item bar is still shown on desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  await gotoMock(page, '/')
  expect(await activeBarContent(page)).toBe('""')
})
