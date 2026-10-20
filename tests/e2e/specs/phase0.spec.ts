import { expect, test, type Page, type WebSocket } from 'playwright/test'

const ownerToken = process.env.E2E_OWNER_TOKEN ?? 'phase0-e2e-owner-token-000000000000'
const label = {
  token: '\uC811\uC18D \uD1A0\uD070',
  login: '\uC811\uC18D\uD558\uAE30',
  loginHeading: '\uB0B4 \uD68C\uC758\uB85D\uC5D0 \uC811\uC18D',
  newMeeting: '\uC0C8 \uD68C\uC758 \uB9CC\uB4E4\uAE30',
  title: '\uD68C\uC758 \uC81C\uBAA9',
  fakeAcknowledgement: '\uAC00\uC0C1 \uC2A4\uD2B8\uB9BC\uC784\uC744 \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4.',
  start: '\uAC00\uC0C1 \uD68C\uC758 \uC2DC\uC791',
  corrected: '\uD45C\uC2DC \uAD50\uC815',
  correctionNotice: '\uC6D0\uBB38 \uBCF4\uC874 \u00B7 \uD45C\uC2DC\uB9CC \uAD50\uC815',
  compareOriginal: '\uC6D0\uBB38\uACFC \uBE44\uAD50',
  stopped: '\uC885\uB8CC\uB428',
  logout: '\uB85C\uADF8\uC544\uC6C3',
}

async function login(page: Page) {
  await page.getByLabel(label.token).fill(ownerToken)
  await page.getByRole('button', { name: label.login }).click()
  await expect(page.getByText('MOCK MODE')).toBeVisible()
}

async function createAndStartMeeting(page: Page, title: string) {
  await page.getByRole('link', { name: label.newMeeting }).click()
  await page.getByLabel(label.title).fill(title)
  await page.getByLabel(label.fakeAcknowledgement).check()
  await page.getByRole('button', { name: label.start }).click()
  await expect(page).toHaveURL(/\/meetings\/[0-9a-f-]+\/live$/)
  const meetingId = page.url().match(/\/meetings\/([^/]+)\/live$/)?.[1]
  expect(meetingId).toBeTruthy()
  return meetingId as string
}

test('denies meeting access when the browser has no authenticated session', async ({ page }) => {
  const unauthorized = await page.request.get('/api/v1/meetings')
  expect(unauthorized.status()).toBe(401)

  await page.goto('/meetings')
  await expect(page.getByRole('heading', { name: label.loginHeading })).toBeVisible()
  await expect(page.getByLabel(label.token)).toBeVisible()
})

test('issues an owner session cookie without storing the owner token in browser storage', async ({ page, context }) => {
  await page.goto('/meetings')
  await login(page)

  const sessionCookie = (await context.cookies()).find((cookie) => cookie.name === 'meeting_session')
  expect(sessionCookie).toMatchObject({ httpOnly: true, sameSite: 'Strict' })
  expect(sessionCookie?.value).toBeTruthy()
  expect(await page.evaluate(() => ({ ...localStorage }))).toEqual({})
  expect(await page.evaluate(() => ({ ...sessionStorage }))).toEqual({})
})

test('persists the fake live transcript and denies access after logout', async ({ page, context }, testInfo) => {
  const liveEvents: Array<Record<string, unknown>> = []
  page.on('websocket', (socket: WebSocket) => {
    if (!socket.url().includes('/api/v1/meetings/')) return
    socket.on('framereceived', ({ payload }) => {
      if (typeof payload !== 'string') return
      try {
        const event = JSON.parse(payload) as Record<string, unknown>
        if (typeof event.type === 'string') liveEvents.push(event)
      } catch {
        // Binary/audio frames are outside the Phase 0 fake stream contract.
      }
    })
  })

  await page.goto('/meetings')
  await login(page)
  const title = `E2E synthetic meeting ${Date.now()}`
  const meetingId = await createAndStartMeeting(page, title)

  await expect.poll(() => liveEvents.length).toBe(6)
  expect(liveEvents.map((event) => event.type)).toEqual([
    'session.ready',
    'transcript.partial',
    'transcript.final',
    'speaker.updated',
    'transcript.corrected',
    'stream.stopped',
  ])
  expect(liveEvents[1]).toEqual(expect.objectContaining({
    type: 'transcript.partial',
    transcriptStatus: 'PARTIAL',
    text: expect.any(String),
  }))
  expect(liveEvents[2]).toEqual(expect.objectContaining({
    type: 'transcript.final',
    transcriptStatus: 'FINAL',
    originalText: expect.any(String),
  }))
  expect(liveEvents[3]).toEqual(expect.objectContaining({
    type: 'speaker.updated',
    speakerLabel: expect.any(String),
    speakerStatus: 'CONFIRMED',
  }))
  expect(liveEvents[4]).toEqual(expect.objectContaining({
    type: 'transcript.corrected',
    transcriptStatus: 'CORRECTED',
    displayText: expect.any(String),
  }))
  expect(liveEvents[4].displayText).not.toBe(liveEvents[4].originalText)
  await expect(page.getByText(label.corrected)).toBeVisible()
  await expect(page.getByText(label.correctionNotice)).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('phase0-live.png'), fullPage: true })
  await page.getByText(label.compareOriginal).click()
  const originalText = await page.locator('details p').textContent()
  expect(originalText?.trim()).toBeTruthy()

  const stopResponse = await page.evaluate(async (id) => {
    const response = await fetch(`/api/v1/meetings/${id}/stop`, { method: 'POST' })
    return { status: response.status, body: await response.json() }
  }, meetingId)
  expect(stopResponse).toEqual(expect.objectContaining({
    status: 200,
    body: expect.objectContaining({ status: 'STOPPED' }),
  }))
  await expect(page.getByText(label.stopped)).toBeVisible()

  await page.reload()
  await expect(page.getByText(label.corrected)).toBeVisible()
  await page.getByText(label.compareOriginal).click()
  await expect(page.locator('details p')).toHaveText(originalText ?? '')

  const transcriptResponse = await page.evaluate(async (id) => {
    const response = await fetch(`/api/v1/meetings/${id}/transcript`)
    return { status: response.status, body: await response.json() }
  }, meetingId)
  expect(transcriptResponse.status).toBe(200)
  expect(transcriptResponse.body).toEqual(expect.arrayContaining([
    expect.objectContaining({
      originalText: originalText?.trim(),
      transcriptStatus: 'CORRECTED',
    }),
  ]))

  await page.getByRole('button', { name: label.logout }).click()
  await expect(page.getByRole('heading', { name: label.loginHeading })).toBeVisible()
  expect((await context.cookies()).find((cookie) => cookie.name === 'meeting_session')).toBeUndefined()

  const deniedAfterLogout = await page.evaluate(async () => (await fetch('/api/v1/meetings')).status)
  expect(deniedAfterLogout).toBe(401)
})

test('stops a fake stream during its partial event without persisting a final segment', async ({ page }) => {
  const eventTypes: string[] = []
  let resolvePartial!: () => void
  const partialReceived = new Promise<void>((resolve) => { resolvePartial = resolve })

  page.on('websocket', (socket) => {
    if (!socket.url().includes('/api/v1/meetings/')) return
    socket.on('framereceived', ({ payload }) => {
      if (typeof payload !== 'string') return
      const event = JSON.parse(payload) as { type?: string }
      if (!event.type) return
      eventTypes.push(event.type)
      if (event.type === 'transcript.partial') resolvePartial()
    })
  })

  await page.goto('/meetings')
  await login(page)
  const meetingId = await createAndStartMeeting(page, `E2E interrupted meeting ${Date.now()}`)
  await partialReceived

  const stopped = await page.evaluate(async (id) => {
    const response = await fetch(`/api/v1/meetings/${id}/stop`, { method: 'POST' })
    return { status: response.status, body: await response.json() }
  }, meetingId)
  expect(stopped).toEqual(expect.objectContaining({
    status: 200,
    body: expect.objectContaining({ status: 'STOPPED' }),
  }))

  await expect.poll(() => eventTypes.includes('stream.stopped')).toBe(true)
  await page.waitForTimeout(1_100)
  expect(eventTypes).not.toContain('transcript.final')
  expect(eventTypes).not.toContain('transcript.corrected')

  const transcript = await page.evaluate(async (id) => {
    const response = await fetch(`/api/v1/meetings/${id}/transcript`)
    return response.json()
  }, meetingId)
  expect(transcript).toEqual([])
})

test('stops the active meeting on logout and allows a new meeting after login', async ({ page }) => {
  const eventTypes: string[] = []
  let resolveReady!: () => void
  let resolveClosed!: () => void
  const ready = new Promise<void>((resolve) => { resolveReady = resolve })
  const closed = new Promise<void>((resolve) => { resolveClosed = resolve })

  page.on('websocket', (socket) => {
    if (!socket.url().includes('/api/v1/meetings/')) return
    socket.on('framereceived', ({ payload }) => {
      if (typeof payload !== 'string') return
      const event = JSON.parse(payload) as { type?: string }
      if (!event.type) return
      eventTypes.push(event.type)
      if (event.type === 'session.ready') resolveReady()
    })
    socket.on('close', resolveClosed)
  })

  await page.goto('/meetings')
  await login(page)
  const loggedOutMeetingId = await createAndStartMeeting(page, `E2E logout meeting ${Date.now()}`)
  await ready
  await page.getByRole('button', { name: label.logout }).click()

  await expect(page.getByRole('heading', { name: label.loginHeading })).toBeVisible()
  await closed
  await page.waitForTimeout(1_100)
  expect(eventTypes).not.toContain('transcript.final')
  expect(eventTypes).not.toContain('transcript.corrected')
  const denied = await page.evaluate(async () => (await fetch('/api/v1/meetings')).status)
  expect(denied).toBe(401)

  await login(page)
  await expect.poll(async () => page.evaluate(async (id) => {
    const response = await fetch(`/api/v1/meetings/${id}`)
    const body = await response.json() as { status: string }
    return body.status
  }, loggedOutMeetingId)).toBe('STOPPED')
  await page.goto('/meetings')
  const replacementMeetingId = await createAndStartMeeting(page, `E2E replacement meeting ${Date.now()}`)
  await expect.poll(() => eventTypes.filter((type) => type === 'session.ready').length).toBe(2)

  const replacementStopped = await page.evaluate(async (id) => {
    const response = await fetch(`/api/v1/meetings/${id}/stop`, { method: 'POST' })
    return response.status
  }, replacementMeetingId)
  expect(replacementStopped).toBe(200)
})
