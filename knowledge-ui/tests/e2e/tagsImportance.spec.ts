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

test.describe('tags + importance (SP4)', () => {
  test.use({ viewport: { width: 1280, height: 800 } })

  test('add and remove a tag on an existing cell', async ({ page }) => {
    await gotoMock(page, '/')
    await openFirstCell(page)

    await page.locator('[data-test="cell-tag-input"]').fill('e2e-tag-x')
    await page.locator('[data-test="cell-tag-input"]').press('Enter')

    const chip = page.locator('[data-test="cell-tag-chip"]', { hasText: 'e2e-tag-x' })
    await expect(chip).toBeVisible()

    await chip.locator('[data-test="cell-tag-remove"]').click()
    await expect(page.locator('[data-test="cell-tag-chip"]', { hasText: 'e2e-tag-x' })).toHaveCount(0)
  })

  test('new cell honours the chosen importance', async ({ page }) => {
    await gotoMock(page, '/')
    await page.locator('[data-test="reader-new"]').first().click()
    await expect(page.locator('[data-test="new-cell-dialog"]')).toBeVisible()

    await page.locator('[data-test="new-cell-importance"]').selectOption('5')
    const cm = page.locator('[data-test="cell-editor-cm"] .cm-content')
    await cm.click()
    await page.keyboard.type('IMPORTANCE_FIVE_CELL')
    await page.locator('[data-test="cell-editor-save"]').click()

    await expect(page.locator('[data-test="new-cell-dialog"]')).toHaveCount(0)
    await expect(page.locator('.reader .chips')).toContainText('★ 5')
  })
})
