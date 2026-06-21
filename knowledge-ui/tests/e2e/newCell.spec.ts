import { test, expect, type Page } from '@playwright/test'

async function gotoMock(page: Page, path: string) {
  await page.addInitScript(() => localStorage.setItem('hivemem_mock', 'true'))
  await page.goto(path)
  await page.waitForTimeout(700)
}

test.describe('new cell (SP4)', () => {
  test.use({ viewport: { width: 1280, height: 800 } })

  test('create a cell from the blank-state New button', async ({ page }) => {
    await gotoMock(page, '/')

    // empty reader state offers a New button
    await page.locator('[data-test="reader-new"]').first().click()
    await expect(page.locator('[data-test="new-cell-dialog"]')).toBeVisible()

    // realm dropdown is prefilled from `list`
    const realm = page.locator('[data-test="new-cell-realm"]')
    await expect(realm).not.toHaveValue('')
    await realm.fill('engineering')
    await page.locator('[data-test="new-cell-signal"]').selectOption('facts')
    await page.locator('[data-test="new-cell-topic"]').fill('my new topic')

    const cm = page.locator('[data-test="cell-editor-cm"] .cm-content')
    await cm.click()
    await page.keyboard.type('NEW_CELL_CONTENT_99')

    const save = page.locator('[data-test="cell-editor-save"]')
    await expect(save).toBeEnabled()
    await save.click()

    // dialog closes and the new cell is shown in the reader with our content + realm
    await expect(page.locator('[data-test="new-cell-dialog"]')).toHaveCount(0)
    await expect(page.locator('.reader')).toBeVisible()
    await expect(page.locator('.reader .title')).toContainText('NEW_CELL_CONTENT_99')
    await expect(page.locator('.reader .chips')).toContainText('engineering')
  })

  test('signal defaults to none and create is blocked until content is typed', async ({ page }) => {
    await gotoMock(page, '/')
    await page.locator('[data-test="reader-new"]').first().click()
    await expect(page.locator('[data-test="new-cell-signal"]')).toHaveValue('')
    // no content yet → CellEditor save disabled
    await expect(page.locator('[data-test="cell-editor-save"]')).toBeDisabled()
  })
})
