import { defineConfig } from 'playwright/test'
import base from './playwright.config'

if (process.env.OFFLINE_RESTART_TEST_ENABLED !== 'true') {
  throw new Error('Set OFFLINE_RESTART_TEST_ENABLED=true to run the isolated API restart test.')
}

export default defineConfig(base, {
  testDir: './faults',
  outputDir: './restart-results',
  timeout: 180_000,
  globalTimeout: 240_000,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'restart-report' }]],
  use: {
    ...base.use,
    baseURL: 'http://127.0.0.1:5182',
    trace: 'retain-on-failure',
  },
})
