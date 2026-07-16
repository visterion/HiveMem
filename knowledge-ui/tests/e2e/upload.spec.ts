import { test, expect, type Page } from '@playwright/test'

async function gotoMock(page: Page, path: string) {
  await page.addInitScript(() => {
    localStorage.setItem('hivemem_mock', 'true')
    localStorage.setItem('hivemem_locale', 'en')
  })
  await page.goto(path)
  await page.locator('.rail-btn').first().waitFor()
}

test.describe('upload page', () => {
  test.use({ viewport: { width: 1280, height: 800 } })

  test('uploads a file and links to the created cell', async ({ page }) => {
    // The upload client bypasses the mock API (own XHR path) → stub the endpoint.
    await page.route('**/api/attachments', route =>
      route.fulfill({ status: 201, contentType: 'application/json',
        body: JSON.stringify({ cell_id: 'cell-xyz', deduplicated: false }) }))

    await gotoMock(page, '/upload')
    await page.locator('[data-test="upload-fab-file"]').setInputFiles({
      name: 'note.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4 test'),
    })
    const job = page.locator('[data-test="upload-job"]')
    await expect(job).toContainText('note.pdf')
    await expect(job.locator('.up-status')).toHaveText('Done', { timeout: 5000 })
    await expect(page.getByText('Open cell')).toBeVisible()
  })

  test('shows an error and offers retry on a failed upload', async ({ page }) => {
    let calls = 0
    await page.route('**/api/attachments', route => {
      calls++
      route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ error: 'boom' }) })
    })
    await gotoMock(page, '/upload')
    await page.locator('[data-test="upload-fab-file"]').setInputFiles({
      name: 'x.png', mimeType: 'image/png', buffer: Buffer.from('89504e47', 'hex'),
    })
    await expect(page.locator('[data-test="upload-job"] .up-status')).toHaveText('Failed')
    await expect(page.getByText('Retry')).toBeVisible()
    expect(calls).toBeGreaterThanOrEqual(1)
  })
})
