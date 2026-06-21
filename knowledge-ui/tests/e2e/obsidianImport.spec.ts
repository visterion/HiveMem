import { test, expect, type Page } from '@playwright/test'
import JSZip from 'jszip'

async function gotoMock(page: Page, path: string) {
  await page.addInitScript(() => {
    localStorage.setItem('hivemem_mock', 'true')
    localStorage.setItem('hivemem_locale', 'en') // stable English assertions
  })
  await page.goto(path)
  await page.waitForTimeout(700)
}

// Build a 52-note vault: each note links to the next (all resolvable) and note-0 also
// links to a missing "Orphan" target (→ one stub). Every note carries a tag + created date.
async function buildVaultZip(): Promise<Buffer> {
  const zip = new JSZip()
  const N = 52
  for (let i = 0; i < N; i++) {
    const next = i < N - 1 ? `\nlinks to [[note-${i + 1}]]` : ''
    const orphan = i === 0 ? '\nand [[Orphan]]' : ''
    zip.file(`vault/note-${i}.md`, `---\ncreated: 2024-01-0${(i % 9) + 1}\ntags: [vault]\n---\nbody #vault${next}${orphan}`)
  }
  return zip.generateAsync({ type: 'nodebuffer' }) as Promise<Buffer>
}

test.describe('obsidian import (SP4)', () => {
  test.use({ viewport: { width: 1280, height: 800 } })

  test('imports a 50+ note vault with tags and wiki-link tunnels', async ({ page }) => {
    await gotoMock(page, '/')
    await page.locator('[data-test="reader-import"]').first().click()
    await expect(page.locator('[data-test="obsidian-dialog"]')).toBeVisible()

    const buffer = await buildVaultZip()
    await page.locator('[data-test="obsidian-file"]').setInputFiles({
      name: 'vault.zip', mimeType: 'application/zip', buffer,
    })

    // dry-run preview reflects the parsed vault
    const preview = page.locator('[data-test="obsidian-preview"]')
    await expect(preview).toBeVisible()
    await expect(preview).toContainText('Notes: 52')

    await page.locator('[data-test="obsidian-realm"]').fill('e2e-vault')
    await page.locator('[data-test="obsidian-run"]').click()

    const done = page.locator('[data-test="obsidian-done"]')
    await expect(done).toBeVisible({ timeout: 15000 })
    // 52 cells + 1 stub (Orphan); 51 sequential links + 1 orphan link = 52 tunnels
    await expect(done).toContainText('52 cells')
    await expect(done).toContainText('1 link stubs')
    await expect(done).toContainText('52 tunnels')
  })

  test('reports an empty archive with no .md files', async ({ page }) => {
    await gotoMock(page, '/')
    await page.locator('[data-test="reader-import"]').first().click()
    const zip = new JSZip()
    zip.file('readme.txt', 'not markdown')
    const buffer = (await zip.generateAsync({ type: 'nodebuffer' })) as Buffer
    await page.locator('[data-test="obsidian-file"]').setInputFiles({
      name: 'empty.zip', mimeType: 'application/zip', buffer,
    })
    await expect(page.locator('[data-test="obsidian-empty"]')).toBeVisible()
    await expect(page.locator('[data-test="obsidian-run"]')).toBeDisabled()
  })
})
