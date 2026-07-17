import { test, expect, type Page } from '@playwright/test'

async function gotoMock(page: Page, path: string) {
  await page.addInitScript(() => localStorage.setItem('hivemem_mock', 'true'))
  await page.goto(path)
  await page.waitForTimeout(700) // mock load + view mount
}

// Selects the first search result so the KnowledgeReader (default view on `/`) shows a cell.
async function openFirstCell(page: Page) {
  await page.locator('.panel input').first().fill('a')
  await page.waitForTimeout(700)
  await page.locator('.stage-results .rows .row').first().click()
}

test.describe('cell editor (SP4)', () => {
  test.use({ viewport: { width: 1280, height: 800 } })

  test('edit → save replaces the cell content with a new revision', async ({ page }) => {
    await gotoMock(page, '/')
    await openFirstCell(page)

    const editBtn = page.locator('[data-test="reader-edit"]')
    await expect(editBtn).toBeVisible({ timeout: 4000 })
    await editBtn.click()

    const cm = page.locator('[data-test="cell-editor-cm"] .cm-content')
    await expect(cm).toBeVisible()
    await cm.click()
    await page.keyboard.press('ControlOrMeta+a')
    await page.keyboard.type('EDITED_CONTENT_MARKER_42')

    const saveBtn = page.locator('[data-test="cell-editor-save"]')
    await expect(saveBtn).toBeEnabled()
    await saveBtn.click()

    // editor closes and the text view shows the freshly saved content
    await expect(page.locator('[data-test="reader-editing"]')).toHaveCount(0)
    await expect(page.locator('.ocr')).toContainText('EDITED_CONTENT_MARKER_42')
  })

  test('save stays disabled until the content actually changes', async ({ page }) => {
    await gotoMock(page, '/')
    await openFirstCell(page)
    await page.locator('[data-test="reader-edit"]').click()
    await expect(page.locator('[data-test="cell-editor-cm"] .cm-content')).toBeVisible()
    // no edits yet → nothing to save
    await expect(page.locator('[data-test="cell-editor-save"]')).toBeDisabled()
  })

  test('cancel leaves the cell unchanged and exits edit mode', async ({ page }) => {
    await gotoMock(page, '/')
    await openFirstCell(page)
    await page.locator('[data-test="reader-edit"]').click()
    const cm = page.locator('[data-test="cell-editor-cm"] .cm-content')
    await cm.click()
    await page.keyboard.type('throwaway text')
    await page.locator('[data-test="cell-editor-cancel"]').click()
    await expect(page.locator('[data-test="reader-editing"]')).toHaveCount(0)
    await expect(page.locator('[data-test="reader-edit"]')).toBeVisible()
  })
})
