import { defineConfig, devices } from '@playwright/test'
export default defineConfig({
  testDir: './tests/e2e',
  // Serialize on CI: the runner has few cores, and parallel workers contend for CPU enough
  // that heavy specs (e.g. the 52-note Obsidian import, ~100 mock calls) balloon past their
  // timeouts. Serial is deterministically green; locally we keep Playwright's default workers.
  workers: process.env.CI ? 1 : undefined,
  use: { headless: true, baseURL: 'http://localhost:5173' },
  webServer: {
    command: 'npm run dev -- --host 0.0.0.0 --port 5173',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 30_000
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }]
})
