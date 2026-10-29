import { test, expect, type Page } from 'playwright/test'

async function mockMeeting(page: Page, inputSource: 'LIVE' | 'LOCAL_RECORDING') {
  const uploads: number[] = []
  let status = 'STARTED'
  let completed = false
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url())
    let body: unknown = {}
    if (url.pathname.endsWith('/ai/configuration')) body = { enabled: true, speechAvailable: true, llmAvailable: true, maximumAudioSeconds: 14400, maximumMeetingUsd: 10 }
    else if (url.pathname.endsWith('/recording-chunks')) uploads.push(Number(url.searchParams.get('sequence')))
    else if (url.pathname.endsWith('/recording-complete')) completed = true
    else if (url.pathname.endsWith('/stop')) status = 'STOPPED'
    else if (url.pathname.endsWith('/transcript') || url.pathname.endsWith('/topics')) body = []
    else if (url.pathname.endsWith('/summary')) { await route.fulfill({ status: 404, json: { message: 'No summary' } }); return }
    if (url.pathname.endsWith('/recording') || url.pathname.endsWith('/recording-complete')) body = { state: completed ? 'COMPLETED' : 'UPLOADING', bytes: 0, durationMs: 0, mimeType: 'audio/webm', chunkCount: uploads.length }
    if (url.pathname.endsWith('/meetings/synthetic') || url.pathname.endsWith('/stop')) body = { id: 'synthetic', title: 'Synthetic reconnect', inputSource, status, startedAt: new Date(Date.now() - 10_000).toISOString(), revision: 1 }
    await route.fulfill({ json: body })
  })
  return uploads
}

async function start(page: Page, name = '마이크 녹음 시작') {
  await page.getByLabel('참여자에게 녹음을 알렸고 녹음에 동의합니다.').check()
  await page.getByRole('button', { name, exact: true }).click()
}

test('retries AI after budget failure while recording keeps uploading', async ({ page }) => {
  const uploads = await mockMeeting(page, 'LIVE')
  let connections = 0
  await page.routeWebSocket('**/api/v1/meetings/synthetic/live?*', socket => {
    connections++
    if (connections === 1) {
      socket.send(JSON.stringify({ type: 'stream.error', meetingId: 'synthetic', message: '월 예산이 부족합니다.' }))
      socket.close()
    } else socket.send(JSON.stringify({ type: 'session.ready', meetingId: 'synthetic', streamSessionId: 'second' }))
  })
  await page.goto('/meetings/synthetic/live')
  await start(page)
  await expect(page.getByText('월 예산이 부족합니다.', { exact: true })).toBeVisible()
  await expect.poll(() => uploads.length, { timeout: 10_000 }).toBeGreaterThan(1)
  await expect(page.getByLabel('전사 누락 구간')).toBeVisible()
  const count = uploads.length
  await page.getByRole('button', { name: 'AI 전사 다시 연결' }).click()
  await expect(page.getByText('실시간 AI 연결됨', { exact: true })).toBeVisible()
  await expect.poll(() => uploads.length, { timeout: 10_000 }).toBeGreaterThan(count)
  expect(connections).toBe(2)
  await expect(page.getByLabel('전사 누락 구간')).toBeVisible()
  await expect(page.getByRole('button', { name: '녹음 종료 및 저장' })).toBeVisible()
  await page.getByRole('button', { name: '녹음 종료 및 저장' }).click()
})

test('reload retains chunks and resumes the same meeting without sequence collisions', async ({ page }) => {
  const uploads = await mockMeeting(page, 'LOCAL_RECORDING')
  await page.goto('/meetings/synthetic/live')
  await start(page)
  await expect.poll(() => uploads.length, { timeout: 10_000 }).toBeGreaterThan(1)
  page.on('dialog', dialog => dialog.accept())
  await page.reload()
  const previous = [...uploads]
  await expect(page.getByRole('button', { name: '청크 복구 후 녹음 재개' })).toBeVisible()
  await start(page, '청크 복구 후 녹음 재개')
  await expect.poll(() => uploads.length, { timeout: 10_000 }).toBeGreaterThan(previous.length)
  expect(uploads[previous.length]).toBeGreaterThan(previous.at(-1)!)
  expect(new Set(uploads).size).toBe(uploads.length)
  await page.getByRole('button', { name: '녹음 종료 및 저장' }).click()
  await expect(page.getByText('서버 저장 완료', { exact: false })).toBeVisible()
})
