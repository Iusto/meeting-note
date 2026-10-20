import { readFile } from 'node:fs/promises'
import type { Page } from 'playwright/test'
import { expect, test } from '../fixtures'

async function download(page: Page, name: string) {
  const pending = page.waitForEvent('download')
  await page.getByRole('button', { name, exact: true }).click()
  const file = await pending
  expect(await file.failure()).toBeNull()
  return readFile((await file.path())!, 'utf8')
}

test('searches saved text, preserves title conflicts and exports original/evidence snapshots', async ({ page }) => {
  await page.goto('/meetings')
  await page.getByRole('link', { name: '새 회의 만들기' }).click()
  const title = `Synthetic export ${Date.now()} 100%_`
  await page.getByLabel('회의 제목', { exact: true }).fill(title)
  await page.getByLabel('가상 스트림임을 확인했습니다.').check()
  await page.getByRole('button', { name: '가상 회의 시작' }).click()
  await expect(page.getByText('종료됨', { exact: true })).toBeVisible()
  const id = page.url().match(/meetings\/([^/]+)/)![1]
  const original = await page.evaluate(async (id) => (await (await fetch(`/api/v1/meetings/${id}/transcript`)).json())[0].originalText, id)
  await page.getByRole('button', { name: '가상 요약 생성', exact: true }).click()
  await expect(page.getByText('MOCK · 요약 버전 1', { exact: true })).toBeVisible()
  const edited = `검색검증-${Date.now()} 배포 일정 목요일`
  await page.getByRole('button', { name: '발언 수정', exact: true }).click()
  await page.getByLabel('표시 문장 수정').fill(edited)
  await page.getByRole('button', { name: '수정 저장', exact: true }).click()
  await expect(page.getByText('발언이 수정되어 요약 갱신이 필요합니다.', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '제목 수정', exact: true }).click()
  await page.getByLabel('새 회의 제목').fill(`${title} 수정`)
  await page.getByRole('button', { name: '제목 저장', exact: true }).click()
  await expect(page.getByRole('heading', { name: `${title} 수정`, exact: true })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: `${title} 수정`, exact: true })).toBeVisible()
  await page.getByRole('button', { name: '제목 수정', exact: true }).click()
  await page.getByLabel('새 회의 제목').fill('이전 버전의 제목 초안')
  const remoteTitle = `${title} 다른 탭 수정`
  await page.evaluate(async ({ id, title }) => {
    const meeting = await (await fetch(`/api/v1/meetings/${id}`)).json()
    const response = await fetch(`/api/v1/meetings/${id}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title, revision: meeting.revision }) })
    if (!response.ok) throw new Error('Synthetic concurrent title fixture failed')
  }, { id, title: remoteTitle })
  await page.getByRole('button', { name: '제목 저장', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('회의가 변경되었습니다.')
  await expect(page.getByLabel('새 회의 제목')).toHaveValue('이전 버전의 제목 초안')
  await page.reload()
  await expect(page.getByRole('heading', { name: remoteTitle, exact: true })).toBeVisible()

  const exported = JSON.parse(await download(page, 'JSON 내보내기'))
  expect(exported.schemaVersion).toBe(1)
  expect(exported.meeting.title).toBe(remoteTitle)
  expect(exported.transcript[0]).toMatchObject({ originalText: original, displayText: edited, userLocked: true })
  expect(exported.summary.stale).toBe(true)
  expect(exported.summary.content.sourceSegmentIds).toContain(exported.transcript[0].id)
  const markdown = await download(page, 'Markdown 내보내기')
  expect(markdown).toContain(edited.replaceAll('-', '\\-'))
  expect(markdown).toContain(original.replace(/[\\`*_{}[\]()#+\-.!|]/g, '\\$&'))
  expect(markdown).toContain(exported.transcript[0].id)
  await page.getByRole('link', { name: '회의 목록', exact: true }).click()
  await page.getByLabel('회의 검색', { exact: true }).fill(edited)
  await page.getByRole('button', { name: '검색', exact: true }).click()
  await expect(page.getByRole('link').filter({ has: page.getByRole('heading', { name: remoteTitle, exact: true }) })).toBeVisible()
  await page.getByLabel('회의 검색', { exact: true }).fill(`없는회의-${Date.now()}`)
  await page.getByRole('button', { name: '검색', exact: true }).click()
  await expect(page.getByRole('heading', { name: '검색 결과가 없습니다', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '전체 보기', exact: true }).click()
  await expect(page.getByRole('heading', { name: remoteTitle, exact: true })).toBeVisible()
})
