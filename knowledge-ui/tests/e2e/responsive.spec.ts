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
    // Since the drawer-default change the search panel starts CLOSED on mobile —
    // open it via the topbar filter button before interacting with it.
    await page.locator('[data-test="mobile-filter"]').click()
    await expect(page.locator('.panel.open')).toBeVisible()
    await page.locator('.panel input').first().fill('a')
    // Results render in the stage now, behind the filter drawer — close the drawer (via the
    // same toggle; the scrim is covered by the drawer's own facets on a 390px screen) to reach them.
    await expect(page.locator('.stage-results .rows .row').first()).toBeVisible({ timeout: 6000 })
    // Tap the scrim on its uncovered right strip (the 366px drawer leaves ~24px on a 390px screen).
    await page.locator('.drawer-scrim').click({ position: { x: 384, y: 420 } })
    await expect(page.locator('.panel.open')).toHaveCount(0)
    await page.locator('.stage-results .rows .row').first().click()
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

  test('settings reachable via topbar gear; rail fits viewport', async ({ page }) => {
    await gotoMock(page, '/')
    // Guard against a vacuous pass: the rail must actually render buttons.
    expect(await page.locator('.rail-btn').count()).toBeGreaterThan(0)
    // Bar passt: kein rail-btn ragt raus
    const overflow = await page.$$eval('.rail-btn', (els, vw) =>
      els.filter(e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.x + r.width > vw + 1 }).length,
      390)
    expect(overflow).toBe(0)
    await page.click('[data-test="tb-settings"]')
    await expect(page).toHaveURL(/settings/)
  })

  test('search drawer starts closed by default', async ({ page }) => {
    await gotoMock(page, '/')
    await expect(page.locator('.panel.open')).toHaveCount(0)
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
