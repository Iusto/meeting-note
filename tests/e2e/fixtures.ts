import { test as base, expect, type APIRequestContext } from 'playwright/test'

type OwnerState = Awaited<ReturnType<APIRequestContext['storageState']>>
const ownerToken = process.env.E2E_OWNER_TOKEN ?? 'phase0-e2e-owner-token-000000000000'
const ownerUsername = 'owner'
const ownerPassword = 'synthetic-owner-password-2026'

// Feature tests reuse one authenticated cookie; every test still gets an isolated
// browser context/IndexedDB. Authentication behavior stays in phase0.spec.ts.
export const test = base.extend<{}, { ownerState: OwnerState }>({
  ownerState: [async ({ playwright }, use) => {
    const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:5173'
    const client = await playwright.request.newContext({ baseURL, extraHTTPHeaders: { Origin: new URL(baseURL).origin } })
    try {
      const configuration = await client.get('/api/v1/session/configuration')
      expect(configuration.ok(), 'Feature-test login configuration must load').toBeTruthy()
      const { setupRequired } = await configuration.json() as { setupRequired: boolean }
      const response = setupRequired
        ? await client.post('/api/v1/session/setup', { data: { token: ownerToken, username: ownerUsername, password: ownerPassword, rememberMe: true } })
        : await client.post('/api/v1/session', { data: { username: ownerUsername, password: ownerPassword, rememberMe: true } })
      expect(response.ok(), 'Feature-test session must authenticate').toBeTruthy()
      await use(await client.storageState())
    } finally { await client.dispose() }
  }, { scope: 'worker' }],
  storageState: async ({ ownerState }, use) => { await use(ownerState) },
})

export { expect }
