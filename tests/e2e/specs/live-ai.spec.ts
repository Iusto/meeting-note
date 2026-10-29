import { expect, test } from '../fixtures'

test('sends real worklet PCM to a fake speech socket and preserves recording after STT failure', async ({ page }) => {
  // Only the browser transport is faked. The API remains paid-disabled and recording uses the real local server.
  await page.route('**/api/v1/ai/configuration', (route) => route.fulfill({ json: {
    enabled: true, speechAvailable: true, llmAvailable: false,
    maximumAudioSeconds: 240, maximumMeetingUsd: 3,
  } }))
  let frames = 0
  let bytes = 0
  await page.routeWebSocket(/\/api\/v1\/meetings\/[^/]+\/live\?audioOffsetMs=/, (socket) => {
    const meetingId = socket.url().match(/meetings\/([^/]+)/)![1]
    socket.send(JSON.stringify({ type: 'session.ready', meetingId, streamSessionId: 'synthetic-speech' }))
    socket.onMessage((message) => {
      if (typeof message === 'string') return
      frames += 1
      bytes += message.byteLength
      expect(message.byteLength % 2).toBe(0)
      if (frames === 3) {
        socket.send(JSON.stringify({ type: 'stream.error', meetingId, message: 'Synthetic STT failure' }))
        socket.close()
      }
    })
  })
  await page.goto('/meetings/new')
  await page.getByLabel('회의 제목').fill(`Synthetic LIVE ${Date.now()}`)
  await page.getByLabel('회의 모드').selectOption('LIVE')
  await page.getByLabel('유료 AI 전송과 녹음에 동의했습니다.').check()
  await page.getByRole('button', { name: 'AI 회의 준비', exact: true }).click()
  await expect(page.getByRole('heading', { name: '마이크 녹음', exact: true })).toBeVisible()
  const meetingId = page.url().match(/meetings\/([^/]+)/)![1]
  try {
    await page.getByLabel('참여자에게 녹음을 알렸고 녹음에 동의합니다.').check()
    await page.getByRole('button', { name: '마이크 녹음 시작', exact: true }).click()
    await expect.poll(() => frames).toBeGreaterThanOrEqual(3)
    expect(bytes).toBeGreaterThanOrEqual(9_600)
    await expect(page.getByRole('button', { name: '녹음 종료 및 저장', exact: true })).toBeVisible()
    await expect(page.getByText(/브라우저 보관: [1-9]/)).toBeVisible({ timeout: 12_000 })
    await page.getByRole('button', { name: '녹음 종료 및 저장', exact: true }).click()
    await expect(page.getByText('서버 저장 완료', { exact: false })).toBeVisible()
    const saved = await page.evaluate(async (id) => ({
      meeting: await (await fetch(`/api/v1/meetings/${id}`)).json(),
      recording: await (await fetch(`/api/v1/meetings/${id}/recording`)).json(),
    }), meetingId)
    expect(saved.meeting.inputSource).toBe('LIVE')
    expect(saved.meeting.status).toBe('STOPPED')
    expect(saved.recording.state).toBe('COMPLETED')
    expect(saved.recording.bytes).toBeGreaterThan(100)
  } finally {
    await page.evaluate(async (id) => { await fetch(`/api/v1/meetings/${id}/stop`, { method: 'POST' }) }, meetingId).catch(() => {})
  }
})
