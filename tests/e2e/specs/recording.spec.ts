import type { Page } from 'playwright/test'
import { expect, test } from '../fixtures'

const createdMeetings = new WeakMap<Page, string>()
test.afterEach(async ({ page }) => {
  const meetingId = createdMeetings.get(page)
  if (meetingId) await page.evaluate(async (id) => { await fetch(`/api/v1/meetings/${id}/stop`, { method: 'POST' }) }, meetingId).catch(() => {})
})

async function createRecording(page: Page) {
  await page.goto('/meetings')
  await page.getByRole('link', { name: '새 회의 만들기' }).click()
  await page.getByLabel('회의 제목').fill(`Synthetic microphone ${Date.now()}`)
  await page.getByLabel('회의 모드').selectOption('LOCAL_RECORDING')
  await page.getByLabel('녹음 전용 모드임을 확인했습니다.').check()
  await page.getByRole('button', { name: '녹음 회의 준비' }).click()
  await expect(page.getByRole('heading', { name: '마이크 녹음', exact: true })).toBeVisible()
  const meetingId = page.url().match(/meetings\/([^/]+)/)![1]
  createdMeetings.set(page, meetingId)
  return meetingId
}

async function startRecording(page: Page) {
  await page.getByLabel('참여자에게 녹음을 알렸고 녹음에 동의합니다.').check()
  await page.getByRole('button', { name: '마이크 녹음 시작' }).click()
  await expect(page.getByRole('button', { name: '녹음 종료 및 저장' })).toBeVisible()
}

test('records synthetic microphone, persists verified audio and plays it after reload', async ({ page }) => {
  const sockets: string[] = []
  page.on('websocket', (socket) => sockets.push(socket.url()))
  const meetingId = await createRecording(page)
  await startRecording(page)
  await expect(page.getByText(/브라우저 보관: [1-9]/)).toBeVisible({ timeout: 12_000 })
  await page.getByRole('button', { name: '녹음 종료 및 저장' }).click()
  await expect(page.getByText('서버 저장 완료', { exact: false })).toBeVisible()
  expect(sockets).toEqual([])
  await page.reload()
  await expect(page.getByText('서버 저장 완료', { exact: false })).toBeVisible()
  const info = await page.evaluate(async (id) => {
    const metadata = await (await fetch(`/api/v1/meetings/${id}/recording`)).json()
    const response = await fetch(`/api/v1/meetings/${id}/recording/audio`)
    const buffer = await response.arrayBuffer()
    const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', buffer)), (n) => n.toString(16).padStart(2, '0')).join('')
    return { metadata, digest, bytes: buffer.byteLength, transcript: await (await fetch(`/api/v1/meetings/${id}/transcript`)).json(), meeting: await (await fetch(`/api/v1/meetings/${id}`)).json() }
  }, meetingId)
  expect(info.metadata.state).toBe('COMPLETED')
  expect(info.metadata.sha256).toBe(info.digest)
  expect(info.metadata.bytes).toBe(info.bytes)
  expect(info.bytes).toBeGreaterThan(100)
  expect(info.transcript).toEqual([])
  expect(info.meeting.status).toBe('STOPPED')
  await page.locator('audio').evaluate((audio: HTMLAudioElement) => audio.play())
  await expect.poll(() => page.locator('audio').evaluate((audio: HTMLAudioElement) => audio.currentTime)).toBeGreaterThan(0.1)
})

test('retains chunks while uploads fail and recovers them after a page reload', async ({ page }) => {
  const meetingId = await createRecording(page)
  await page.route('**/recording-chunks?*', (route) => route.abort('failed'))
  await startRecording(page)
  await expect(page.getByText(/브라우저 보관: [2-9]/)).toBeVisible({ timeout: 14_000 })
  await expect(page.getByText(/업로드 대기 [2-9]/)).toBeVisible()
  await page.getByRole('button', { name: '녹음 종료 및 저장' }).click()
  await expect(page.getByRole('button', { name: '보관한 청크 복구' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('button', { name: '보관한 청크 복구' })).toBeVisible()
  await page.unroute('**/recording-chunks?*')
  await page.getByRole('button', { name: '보관한 청크 복구' }).click()
  await expect(page.getByText('서버 저장 완료', { exact: false })).toBeVisible()
  expect(await page.evaluate(async (id) => (await (await fetch(`/api/v1/meetings/${id}/recording`)).json()).chunkCount, meetingId)).toBeGreaterThanOrEqual(2)
  await expect(page.getByRole('button', { name: '보관한 청크 복구' })).toHaveCount(0)
})

test('prevents another tab from overwriting an active recording', async ({ page, context }) => {
  const meetingId = await createRecording(page)
  await startRecording(page)
  const other = await context.newPage()
  await other.goto(`/meetings/${meetingId}/live`)
  await expect(other.getByRole('button', { name: '보관한 청크 복구' })).toBeVisible()
  await other.getByRole('button', { name: '보관한 청크 복구' }).click()
  await expect(other.getByText('다른 탭에서 이 녹음을 사용 중입니다.')).toBeVisible()
  await other.close()
  await page.getByRole('button', { name: '녹음 종료 및 저장' }).click()
  await expect(page.getByText('서버 저장 완료', { exact: false })).toBeVisible()
})
