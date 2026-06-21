import { test, expect, type Page } from '@playwright/test'

async function gotoMock(page: Page, path: string) {
  await page.addInitScript(() => localStorage.setItem('hivemem_mock', 'true'))
  await page.goto(path)
  await page.waitForTimeout(700)
}

async function openFirstCell(page: Page) {
  await page.locator('.panel input').first().fill('a')
  await page.waitForTimeout(700)
  await page.locator('.panel .panel-body .rows .row').first().click()
  await expect(page.locator('.reader')).toBeVisible({ timeout: 4000 })
}

test.describe('tunnel editor (SP4)', () => {
  test.use({ viewport: { width: 1280, height: 800 } })

  test('link the current cell to another via the search picker', async ({ page }) => {
    await gotoMock(page, '/')
    await openFirstCell(page)

    const editor = page.locator('[data-test="tunnel-editor"]')
    await expect(editor).toBeVisible()
    const before = await page.locator('[data-test="tunnel-row"]').count()

    await page.locator('[data-test="tunnel-search"]').fill('a')
    const results = page.locator('[data-test="tunnel-result"]')
    await expect(results.first()).toBeVisible({ timeout: 4000 })
    await results.first().click()

    await page.locator('[data-test="tunnel-relation"]').selectOption('builds_on')
    await page.locator('[data-test="tunnel-note"]').fill('linked via e2e')

    const addBtn = page.locator('[data-test="tunnel-add"]')
    await expect(addBtn).toBeEnabled()
    await addBtn.click()

    await expect(page.locator('[data-test="tunnel-row"]')).toHaveCount(before + 1)
    await expect(page.locator('[data-test="tunnel-existing"]')).toContainText('builds_on')
  })

  test('link button is disabled until a target cell is picked', async ({ page }) => {
    await gotoMock(page, '/')
    await openFirstCell(page)
    await expect(page.locator('[data-test="tunnel-add"]')).toBeDisabled()
  })
})
