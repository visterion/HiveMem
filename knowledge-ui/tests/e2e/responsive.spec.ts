import { test, expect, type Page } from '@playwright/test'

const ROUTES = ['/', '/hive', '/graph', '/realms', '/photos', '/scans', '/timemachine', '/queen', '/settings']
const VIEWPORTS = [
  { name: 'mobile', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 800 },
]

async function gotoMock(page: Page, path: string) {
  await page.addInitScript(() => localStorage.setItem('hivemem_mock', 'true'))
  await page.goto(path)
  await page.waitForTimeout(700) // mock load + view mount
}

for (const vp of VIEWPORTS) {
  test.describe(`${vp.name} (${vp.width})`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height } })
    for (const route of ROUTES) {
      test(`${route} no horizontal overflow`, async ({ page }) => {
        await gotoMock(page, route)
        const res = await page.evaluate(() => {
          const de = document.documentElement
          const docOverflow = de.scrollWidth - de.clientWidth
          // An element legitimately extending past the viewport edge is fine *iff* an
          // ancestor scrolls it horizontally (overflow-x: auto/scroll) — same intentional
          // pattern as the bottom rail (the photo grid and queen KPI strip scroll this way).
          // Such elements don't cause page-level overflow; flag only true layout escapes.
          const inScrollContainer = (el: Element): boolean => {
            let cur: Element | null = el.parentElement
            while (cur && cur !== document.body) {
              const ox = getComputedStyle(cur).overflowX
              if (ox === 'auto' || ox === 'scroll') return true
              cur = cur.parentElement
            }
            return false
          }
          let worst = ''
          for (const el of Array.from(document.querySelectorAll('body *'))) {
            const r = el.getBoundingClientRect()
            if (r.right > window.innerWidth + 2 && r.width < 4000) {
              if (inScrollContainer(el)) continue
              worst = (el.className || el.tagName).toString().slice(0, 60); break
            }
          }
          return { docOverflow, worst }
        })
        expect(res.docOverflow, `document overflow on ${route}`).toBeLessThanOrEqual(1)
        expect(res.worst, `offscreen element on ${route}`).toBe('')
      })
    }
  })
}

test.describe('mobile flows (390)', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('bottom rail strip is pinned to the bottom with all entries', async ({ page }) => {
    await gotoMock(page, '/')
    const rail = page.locator('.rail')
    await expect(rail).toBeVisible()
    const box = await rail.boundingBox()
    expect(box!.y).toBeGreaterThan(400) // bottom half of an 844px screen
    expect(await page.locator('.rail .rail-btn').count()).toBeGreaterThanOrEqual(8)
  })

  test('search → tap result → inspector overlay on-screen (regression: "click opens nothing")', async ({ page }) => {
    await gotoMock(page, '/')
    await page.locator('.panel input').first().fill('a')
    await page.waitForTimeout(700)
    await page.locator('.panel .panel-body .rows .row').first().click()
    const insp = page.locator('.inspector')
    await expect(insp).toBeVisible({ timeout: 4000 })
    const box = await insp.boundingBox()
    expect(box!.x).toBeLessThan(390)
    expect(box!.width).toBeGreaterThan(100)
  })

  test('scans filter drawer is closed by default and opens via the filter button', async ({ page }) => {
    await gotoMock(page, '/scans')
    await page.waitForTimeout(700)
    await expect(page.locator('.panel.open')).toHaveCount(0)
    await page.locator('[data-test="mobile-filter"]').click()
    await expect(page.locator('.panel.open')).toBeVisible()
  })

  test('tapping a scan opens the fullscreen document view within the viewport', async ({ page }) => {
    await gotoMock(page, '/scans')
    await page.waitForTimeout(700)
    // Tapping the thumbnail opens the document directly (no intermediate detail modal).
    await page.locator('.doccard .dc-thumb').first().click()
    const reader = page.locator('.reader-shell')
    await expect(reader).toBeVisible()
    // No page-level horizontal overflow while the fullscreen reader is open.
    const overflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(overflow).toBeLessThanOrEqual(1)
  })

  test('tapping a scan title opens the overview tab (summaries + raw text)', async ({ page }) => {
    await gotoMock(page, '/scans')
    await page.waitForTimeout(700)
    await page.locator('.doccard .dc-body').first().click()
    await expect(page.locator('.reader-shell')).toBeVisible()
    // The overview tab (DocInfoTab) is the active landing view for a title tap.
    await expect(page.locator('.dinfo')).toBeVisible()
  })
})

test.describe('mobile chrome collisions', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('tweaks FAB does not overlap any bottom-rail button', async ({ page }) => {
    await gotoMock(page, '/')
    const fab = await page.locator('[data-test="tweaks-toggle"]').boundingBox()
    expect(fab).toBeTruthy()
    const buttons = page.locator('.rail .rail-btn')
    const count = await buttons.count()
    expect(count).toBeGreaterThan(0)
    for (let i = 0; i < count; i++) {
      const b = await buttons.nth(i).boundingBox()
      if (!b || !fab) continue
      const overlaps =
        fab.x < b.x + b.width && fab.x + fab.width > b.x &&
        fab.y < b.y + b.height && fab.y + fab.height > b.y
      expect(overlaps, `FAB overlaps rail button #${i}`).toBe(false)
    }
  })
})

test.describe('desktop (1280) keeps the multi-column shell', () => {
  test.use({ viewport: { width: 1280, height: 800 } })
  test('rail is a left vertical column, no mobile filter button', async ({ page }) => {
    await gotoMock(page, '/')
    const box = await page.locator('.rail').boundingBox()
    expect(box!.x).toBeLessThan(10)        // left edge
    expect(box!.height).toBeGreaterThan(400) // tall vertical rail
    await expect(page.locator('[data-test="mobile-filter"]')).toHaveCount(0)
  })
})
