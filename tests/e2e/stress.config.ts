import { defineConfig } from 'playwright/test'
import base from './playwright.config'

export default defineConfig(base, {
  testDir: './stress',
  outputDir: './stress-results',
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'stress-report' }]],
  use: { trace: 'off', screenshot: 'only-on-failure' },
})
