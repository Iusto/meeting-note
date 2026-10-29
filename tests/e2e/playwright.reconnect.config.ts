import { defineConfig } from 'playwright/test'

export default defineConfig({
  testDir: './specs', testMatch: 'reconnect.spec.ts', workers: 1, timeout: 30_000,
  use: {
    baseURL: 'http://localhost:5189', headless: true, browserName: 'chromium',
    launchOptions: {
      executablePath: process.env.E2E_CHROME_EXECUTABLE ?? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
      args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
    },
  },
  webServer: process.env.E2E_EXTERNAL_WEB ? undefined : {
    command: 'npm run dev -- --port 5189', cwd: '../../apps/web',
    url: 'http://localhost:5189', reuseExistingServer: false,
  },
})
