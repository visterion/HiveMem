import { test, expect, type Page } from '@playwright/test'

async function gotoMock(page: Page, path: string) {
  await page.addInitScript(() => {
    localStorage.setItem('hivemem_mock', 'true')
    localStorage.setItem('hivemem_locale', 'en')
  })
  await page.goto(path)
  await page.locator('.rail-btn').first().waitFor()
}

/**
 * Selects a file via the hidden FAB input and waits for the resulting job row to
 * appear, retrying the selection if it doesn't. On a cold dev-server start (fresh
 * CI checkout, no vite cache) Vite's on-demand dependency pre-bundling can discover
 * a new dep mid-navigation and force a full page reload shortly after the initial
 * load — which silently wipes a just-set native file input (and its change event)
 * before Vue ever processes it. Retrying makes the test independent of that
 * one-time, environment-dependent reload instead of racing it.
 */
async function pickFile(page: Page, file: { name: string; mimeType: string; buffer: Buffer }) {
  const input = page.locator('[data-test="upload-fab-file"]')
  const job = page.locator('[data-test="upload-job"]')
  for (let attempt = 1; attempt <= 4; attempt++) {
    await input.setInputFiles(file)
    try {
      await job.waitFor({ timeout: 3000 })
      return
    } catch {
      if (attempt === 4) throw new Error('upload-job never appeared after retries')
    }
  }
}

test.describe('upload page', () => {
  test.use({ viewport: { width: 1280, height: 800 } })

  test('uploads a file and links to the created cell', async ({ page }) => {
    // The upload client bypasses the mock API (own XHR path) → stub the endpoint.
    await page.route('**/api/attachments', route =>
      route.fulfill({ status: 201, contentType: 'application/json',
        body: JSON.stringify({ cell_id: 'cell-xyz', deduplicated: false }) }))

    await gotoMock(page, '/upload')
    await pickFile(page, { name: 'note.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4 test') })
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
    await pickFile(page, { name: 'x.png', mimeType: 'image/png', buffer: Buffer.from('89504e47', 'hex') })
    await expect(page.locator('[data-test="upload-job"] .up-status')).toHaveText('Failed')
    await expect(page.getByText('Retry')).toBeVisible()
    expect(calls).toBeGreaterThanOrEqual(1)
  })
})
