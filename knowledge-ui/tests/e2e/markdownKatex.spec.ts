import { test, expect } from '@playwright/test'

// Regression guard for the H7 follow-up (commit 23623be): the CJS default export
// of @vscode/markdown-it-katex must be unwrapped correctly under Vite's dev-mode
// esbuild dependency prebundling. When it wasn't, `md.use(plugin)` threw
// "plugin.apply is not a function" and MarkdownTab crashed on every render — a
// failure mode vitest's module resolution structurally cannot reproduce, so it
// needs a real Vite build surface. The fixture mounts the real MarkdownTab.vue
// through the dev server's module graph.
test.describe('MarkdownTab KaTeX (real Vite build path)', () => {
  test('renders inline math as .katex without crashing, and leaves plain dollar text literal', async ({ page }) => {
    const crashes: string[] = []
    page.on('pageerror', e => crashes.push(String(e)))

    await page.goto('/tests/e2e/fixtures/markdown-katex.html')

    // The component mounted and rendered its markdown article (proves md.use()
    // did not throw during setup).
    const article = page.locator('.md')
    await expect(article).toBeVisible()

    // Real inline math ($x^2$) produced a KaTeX element.
    await expect(page.locator('.katex').first()).toBeVisible()

    // Plain-text dollar amounts were NOT turned into math.
    await expect(article).toContainText('Costs are $50 and $60 today.')

    // The dollar inside the code span stayed literal (no katex there).
    const code = page.locator('.md code').first()
    await expect(code).toHaveText('echo $HOME')

    // No "plugin.apply is not a function" (or any) uncaught error during mount.
    expect(crashes, crashes.join('\n')).toEqual([])
  })
})
