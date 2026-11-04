import { expect, test, type BrowserContext, type WebSocketRoute } from 'playwright/test'

test('two devices automatically watch, share edits and recover missed finals without acquiring microphones', async ({ browser, baseURL }) => {
  const phone = await browser.newContext({ baseURL, viewport: { width: 390, height: 844 } })
  const laptop = await browser.newContext({ baseURL })
  const viewers = new Map<BrowserContext, WebSocketRoute>()
  const meeting = { id: 'synthetic', title: 'Synthetic shared meeting', status: 'STARTED', inputSource: 'LIVE', revision: 0 }
  const segment = (id: string) => ({ segmentId: id, eventId: id, sequence: Number(id.slice(1)), startMs: 0, endMs: 900,
    originalText: `Original ${id}`, displayText: `Final ${id}`, speakerLabel: 'speaker_0', speakerStatus: 'TENTATIVE', transcriptStatus: 'FINAL', revision: 0 })
  const segments = [segment('s1')]
  const names = [{ label: 'speaker_0', displayName: null as string | null, mergedInto: null }]
  let opens = 0
  let aiRequests = 0
  const broadcast = (event: object) => { for (const socket of viewers.values()) socket.send(JSON.stringify({ meetingId: 'synthetic', ...event })) }
  try {
    for (const context of [phone, laptop]) {
      await context.addInitScript(() => {
        const original = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices)
        Object.assign(window, { microphoneRequests: 0 })
        navigator.mediaDevices.getUserMedia = constraints => {
          Object.assign(window, { microphoneRequests: (window as unknown as { microphoneRequests: number }).microphoneRequests + 1 })
          return original(constraints)
        }
      })
      await context.route('**/api/v1/**', async route => {
        const path = new URL(route.request().url()).pathname
        let body: unknown = {}
        if (path.endsWith('/transcript')) body = segments
        else if (path.endsWith('/speaker-overrides')) body = []
        else if (path.endsWith('/speakers')) {
          if (route.request().method() === 'PUT') {
            names[0].displayName = route.request().postDataJSON().displayName
            broadcast({ type: 'meeting.changed' })
            body = names[0]
          } else body = names
        } else if (/\/segments\/s1$/.test(path)) {
          segments[0] = { ...segments[0], displayText: route.request().postDataJSON().displayText, revision: 1 }
          broadcast({ type: 'meeting.changed' }); body = segments[0]
        } else if (path.endsWith('/meetings/synthetic')) body = meeting
        else if (path.endsWith('/summary')) {
          if (route.request().method() !== 'GET') aiRequests++
          return route.fulfill({ status: 404, json: { message: 'No saved summary' } })
        } else if (path.endsWith('/recording')) return route.fulfill({ status: 404, json: {} })
        else if (path.endsWith('/ai/configuration')) body = { enabled: true, speechAvailable: true, llmAvailable: true, llmProvider: 'CODEX' }
        await route.fulfill({ json: body })
      })
      await context.routeWebSocket(/\/api\/v1\/meetings\/synthetic\/live/, socket => {
        expect(new URL(socket.url()).searchParams.get('mode')).toBe('watch')
        viewers.set(context, socket); opens++
        socket.send(JSON.stringify({ type: 'session.ready', meetingId: 'synthetic' }))
        socket.onClose(() => { if (viewers.get(context) === socket) viewers.delete(context) })
      })
    }
    const mobile = await phone.newPage()
    const desktop = await laptop.newPage()
    await Promise.all([mobile.goto('/meetings/synthetic/live'), desktop.goto('/meetings/synthetic/live')])
    for (const page of [mobile, desktop]) {
      await expect(page.getByText('실시간 보기 연결됨', { exact: true })).toBeVisible()
      await expect(page.locator('#segment-s1')).toContainText('Final s1')
    }
    broadcast({ type: 'transcript.partial', eventId: 's2', sequence: 2, text: 'Interim s2', startMs: 1000 })
    await expect(desktop.getByText('Interim s2', { exact: true })).toBeVisible()
    segments.push(segment('s2'))
    broadcast({ type: 'transcript.final', ...segments[1] })
    for (const page of [mobile, desktop]) await expect(page.locator('#segment-s2')).toContainText('Final s2')
    await expect(desktop.getByText('Interim s2', { exact: true })).toHaveCount(0)

    const panel = desktop.locator('details').filter({ has: desktop.locator('summary', { hasText: '화자 이름 설정' }) }).first()
    await panel.getByRole('textbox').first().fill('김정규')
    await panel.getByRole('button', { name: '이름 저장', exact: true }).first().click()
    await expect(mobile.locator('#segment-s1')).toContainText('김정규')
    // A remote manual correction follows the same notification/storage path.
    segments[0] = { ...segments[0], displayText: 'Reviewed s1', revision: 1 }
    broadcast({ type: 'meeting.changed' })
    await expect(mobile.locator('#segment-s1')).toContainText('Reviewed s1')

    const disconnected = viewers.get(laptop)!
    viewers.delete(laptop)
    disconnected.close({ code: 1012, reason: 'Synthetic offline interval' })
    segments.push(segment('s3')) // persisted while this viewer is offline; no replay event
    await expect.poll(() => opens).toBeGreaterThan(2)
    await expect(desktop.locator('#segment-s3')).toContainText('Final s3')
    await expect(desktop.locator('#segment-s1')).toContainText('Reviewed s1')
    for (const page of [mobile, desktop]) {
      expect(await page.evaluate(() => (window as unknown as { microphoneRequests: number }).microphoneRequests)).toBe(0)
    }
    await desktop.close()
    segments.push(segment('s4'))
    broadcast({ type: 'transcript.final', ...segments[3] })
    await expect(mobile.locator('#segment-s4')).toContainText('Final s4')
    expect(aiRequests).toBe(0)
    expect(await mobile.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
  } finally { await phone.close(); await laptop.close() }
})
