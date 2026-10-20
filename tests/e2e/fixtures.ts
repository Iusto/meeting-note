import { test as base, expect, type APIRequestContext } from 'playwright/test'

type OwnerState = Awaited<ReturnType<APIRequestContext['storageState']>>

// Feature tests reuse one authenticated cookie; every test still gets an isolated
// browser context/IndexedDB. Authentication behavior stays in phase0.spec.ts.
export const test = base.extend<{}, { ownerState: OwnerState }>({
  ownerState: [async ({ playwright }, use) => {
    const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:5173'
    const client = await playwright.request.newContext({ baseURL, extraHTTPHeaders: { Origin: new URL(baseURL).origin } })
    try {
      const response = await client.post('/api/v1/session', { data: { token: process.env.E2E_OWNER_TOKEN ?? 'phase0-e2e-owner-token-000000000000' } })
      expect(response.ok(), 'Feature-test session must authenticate').toBeTruthy()
      await use(await client.storageState())
    } finally { await client.dispose() }
  }, { scope: 'worker' }],
  storageState: async ({ ownerState }, use) => { await use(ownerState) },
})

export { expect }
