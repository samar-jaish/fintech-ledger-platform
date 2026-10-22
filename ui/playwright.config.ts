import { defineConfig, devices } from '@playwright/test';

// Browser tests run against the running platform (Docker Compose or kind): the real UI, real Keycloak and real APIs.
//   docker compose --profile apps up -d --build   then   npm run e2e
export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.UI_URL ?? 'http://localhost:8085',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    // Normally Playwright uses the browser it downloaded itself. Set this to use one already installed.
    launchOptions: process.env.PLAYWRIGHT_CHROMIUM_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH } : {},
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
