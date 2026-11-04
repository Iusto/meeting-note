import { defineConfig } from 'playwright/test'
import reconnect from './playwright.reconnect.config'

export default defineConfig({
  ...reconnect,
  testMatch: ['watch.spec.ts', 'speaker-search.spec.ts', 'reconnect.spec.ts'],
})
