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

test.describe('export .md (SP4)', () => {
  test.use({ viewport: { width: 1280, height: 800 } })

  test('Export downloads a .md file with frontmatter', async ({ page }) => {
    await gotoMock(page, '/')
    await openFirstCell(page)

    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.locator('[data-test="reader-export"]').click(),
    ])

    expect(download.suggestedFilename()).toMatch(/\.md$/)
    const stream = await download.createReadStream()
    const chunks: string[] = []
    for await (const c of stream) chunks.push(String(c))
    const text = chunks.join('')
    expect(text.startsWith('---\n')).toBe(true)
    expect(text).toContain('realm:')
  })
})
