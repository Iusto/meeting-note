import { expect, test } from '../fixtures'

const minutes = Number(process.env.OFFLINE_STRESS_MINUTES)
if (!Number.isInteger(minutes) || minutes < 1 || minutes > 240) {
  throw new Error('Set OFFLINE_STRESS_MINUTES to an integer from 1 to 240; only synthetic microphone input is used.')
}

test('sustains synthetic recording through an upload outage and verifies the completed audio', async ({ page }, testInfo) => {
  test.setTimeout(minutes * 60_000 + 120_000)
  let meetingId: string | undefined
  const sockets: string[] = []
  page.on('websocket', (socket) => sockets.push(socket.url()))
  const checkpoints: { elapsedMs: number; browserStorage: string; state: string }[] = []
  try {
    await page.goto('/meetings')
    await page.getByRole('link', { name: '새 회의 만들기' }).click()
    await page.getByLabel('회의 제목').fill(`Synthetic ${minutes} minute recording ${Date.now()}`)
    await page.getByLabel('회의 모드').selectOption('LOCAL_RECORDING')
    await page.getByLabel('녹음 전용 모드임을 확인했습니다.').check()
    await page.getByRole('button', { name: '녹음 회의 준비' }).click()
    await expect(page.getByRole('heading', { name: '마이크 녹음', exact: true })).toBeVisible()
    meetingId = page.url().match(/meetings\/([^/]+)/)![1]
    await page.getByLabel('참여자에게 녹음을 알렸고 녹음에 동의합니다.').check()
    await page.getByRole('button', { name: '마이크 녹음 시작' }).click()
    await expect(page.getByRole('button', { name: '녹음 종료 및 저장' })).toBeVisible()
    // Recording metadata uses the browser's monotonic clock. Node's wall clock
    // can drift or be adjusted independently during a multi-hour run.
    const wallStarted = Date.now()
    const started = await page.evaluate(() => performance.now())
    await expect(page.getByText(/브라우저 보관: [1-9]/)).toBeVisible({ timeout: 12_000 })
    await page.route('**/recording-chunks?*', (route) => route.abort('failed'))
    await expect(page.getByText(/업로드 대기 [2-9]/)).toBeVisible({ timeout: 14_000 })
    await page.unroute('**/recording-chunks?*')
    let elapsedMs = await page.evaluate((start) => performance.now() - start, started)
    while (elapsedMs < minutes * 60_000) {
      await page.waitForTimeout(Math.min(30_000, minutes * 60_000 - elapsedMs))
      const metadata = await page.evaluate(async (id) => {
        const response = await fetch(`/api/v1/meetings/${id}/recording`)
        if (!response.ok) throw new Error(`Recording metadata: ${response.status}`)
        return response.json()
      }, meetingId)
      const browserStorage = await page.getByText(/브라우저 보관: [1-9]/).innerText()
      elapsedMs = await page.evaluate((start) => performance.now() - start, started)
      checkpoints.push({ elapsedMs, browserStorage, state: metadata.state })
      expect(metadata.state).toBe('UPLOADING')
      await expect(page.getByRole('button', { name: '녹음 종료 및 저장' })).toBeVisible()
      console.log(`Synthetic recording: ${Math.round(elapsedMs / 1000)}s, ${browserStorage}`)
    }
    const elapsedBeforeStop = await page.evaluate((start) => performance.now() - start, started)
    const wallElapsedBeforeStop = Date.now() - wallStarted
    await page.getByRole('button', { name: '녹음 종료 및 저장' }).click()
    await expect(page.getByText('서버 저장 완료', { exact: false })).toBeVisible({ timeout: 60_000 })
    await page.reload()
    await expect(page.getByText('서버 저장 완료', { exact: false })).toBeVisible()
    const result = await page.evaluate(async (id) => {
      async function json(path: string) {
        const response = await fetch(`/api/v1/meetings/${id}${path}`)
        if (!response.ok) throw new Error(`Verification ${path}: ${response.status}`)
        return response.json()
      }
      const response = await fetch(`/api/v1/meetings/${id}/recording/audio`)
      if (!response.ok) throw new Error(`Audio: ${response.status}`)
      const buffer = await response.arrayBuffer()
      const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', buffer)), (n) => n.toString(16).padStart(2, '0')).join('')
      return { metadata: await json('/recording'), meeting: await json(''), transcript: await json('/transcript'), digest, bytes: buffer.byteLength }
    }, meetingId)
    // Preserve evidence even when an assertion below fails.
    await testInfo.attach('recording-metrics', {
      body: JSON.stringify({ requestedMinutes: minutes, elapsedBeforeStop, wallElapsedBeforeStop, checkpoints, metadata: result.metadata, downloadedBytes: result.bytes, downloadedSha256: result.digest }, null, 2),
      contentType: 'application/json',
    })
    expect(result.metadata.state).toBe('COMPLETED')
    expect(result.metadata.sha256).toBe(result.digest)
    expect(result.metadata.bytes).toBe(result.bytes)
    expect(result.metadata.durationMs).toBeGreaterThanOrEqual(minutes * 60_000 - 2_000)
    expect(result.metadata.durationMs).toBeGreaterThanOrEqual(elapsedBeforeStop - 2_000)
    expect(result.metadata.chunkCount).toBeGreaterThanOrEqual(Math.floor(minutes * 60 / 3))
    expect(result.bytes).toBeGreaterThan(100)
    expect(result.transcript).toEqual([])
    expect(result.meeting.status).toBe('STOPPED')
    expect(sockets).toEqual([])
    await page.locator('audio').evaluate((audio: HTMLAudioElement) => audio.play())
    await expect.poll(() => page.locator('audio').evaluate((audio: HTMLAudioElement) => audio.currentTime)).toBeGreaterThan(0.1)
  } finally {
    if (meetingId) await page.evaluate(async (id) => { await fetch(`/api/v1/meetings/${id}/stop`, { method: 'POST' }) }, meetingId).catch(() => {})
  }
})
