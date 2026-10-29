import type { Page } from 'playwright/test'
import { expect, test } from '../fixtures'

async function login(page: Page) {
  await page.goto('/meetings')
  await expect(page.getByRole('link', { name: '주제·용어 사전' })).toBeVisible()
}

test('persists topic/term edits and sends only individually approved keyterms', async ({ page }) => {
  await login(page)
  await page.getByRole('link', { name: '주제·용어 사전' }).click()
  const name = `Synthetic topic ${Date.now()}`
  await page.getByLabel('새 주제', { exact: true }).fill(name)
  await page.getByRole('button', { name: '주제 추가', exact: true }).click()
  await expect(page.getByLabel('주제 이름')).toHaveValue(name)
  const topicId = await page.evaluate(async (name) => (await (await fetch('/api/v1/topics')).json()).find((topic: { name: string }) => topic.name === name).id, name)
  await page.getByLabel('설명', { exact: true }).fill('가상 데이터로 사전 승인을 검증합니다.')
  await page.getByRole('button', { name: '주제 수정 저장' }).click()
  await expect(page.getByText('주제를 수정했습니다.', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '가상 용어 후보 불러오기' }).click()
  const lot = page.getByRole('article', { name: '용어 LOT', exact: true })
  await expect(lot.getByText('승인 대기', { exact: false })).toBeVisible()
  expect(await page.evaluate(async (id) => (await fetch(`/api/v1/topics/${id}/keyterms`)).json(), topicId)).toEqual([])
  await lot.getByRole('button', { name: '이 용어 승인' }).click()
  await expect(lot.getByText('승인됨', { exact: false })).toBeVisible()
  expect(await page.evaluate(async (id) => (await fetch(`/api/v1/topics/${id}/keyterms`)).json(), topicId)).toEqual(['LOT'])
  const manual = `WIP-${Date.now()}`
  await page.getByLabel('표준어', { exact: true }).fill(manual)
  await page.getByLabel('용어 뜻').fill('재공품')
  await page.getByLabel('별칭 · 쉼표로 구분').fill('재공, 위아이피')
  await page.getByRole('button', { name: '용어 저장', exact: true }).click()
  const term = page.getByRole('article', { name: `용어 ${manual}`, exact: true })
  await expect(term.getByText('승인 대기', { exact: false })).toBeVisible()
  await term.getByRole('button', { name: '용어 수정', exact: true }).click()
  await page.getByLabel('용어 뜻').fill('생산 중인 재공품')
  await page.getByLabel('용어 승인 상태').selectOption('APPROVED')
  await page.getByRole('button', { name: '용어 저장', exact: true }).click()
  await expect(term.getByText('생산 중인 재공품', { exact: true })).toBeVisible()
  await page.reload()
  await page.getByRole('button', { name, exact: true }).click()
  await expect(term.getByText('승인됨', { exact: false })).toBeVisible()
  page.on('dialog', (dialog) => dialog.accept())
  await term.getByRole('button', { name: '용어 삭제', exact: true }).click()
  await expect(term).toHaveCount(0)
  await page.getByRole('button', { name: '주제 삭제', exact: true }).click()
  await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0)
})

test('preserves original and user edits while marking saved mock summaries stale', async ({ page }) => {
  await login(page)
  const topicName = `Synthetic correction ${Date.now()}`
  const topicId = await page.evaluate(async (name) => {
    const topic = await (await fetch('/api/v1/topics', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name, description: '가상 교정 테스트' }) })).json()
    const created = await fetch(`/api/v1/topics/${topic.id}/terms`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ canonical: '릴리스', meaning: '가상 테스트의 배포 표현', aliases: ['배포'], status: 'APPROVED' }) })
    if (!created.ok) throw new Error('Synthetic glossary fixture could not be created')
    return topic.id
  }, topicName)
  await page.getByRole('link', { name: '새 회의 만들기' }).click()
  await page.getByLabel('회의 제목').fill(`Synthetic summary ${Date.now()}`)
  await page.getByLabel('회의 주제').selectOption(topicId)
  await page.getByLabel('가상 스트림임을 확인했습니다.').check()
  await page.getByRole('button', { name: '가상 회의 시작' }).click()
  await expect(page.getByText('종료됨', { exact: true })).toBeVisible()
  const id = page.url().match(/meetings\/([^/]+)/)![1]
  await page.getByRole('button', { name: '가상 요약 생성', exact: true }).click()
  const summary = page.getByRole('region', { name: '저장된 요약' })
  await expect(summary.getByText('MOCK · 요약 버전 1', { exact: true })).toBeVisible()
  const first = await page.evaluate(async (id) => ({
    transcript: await (await fetch(`/api/v1/meetings/${id}/transcript`)).json(),
    summary: await (await fetch(`/api/v1/meetings/${id}/summary`)).json(),
  }), id)
  expect(first.summary.stale).toBe(false)
  expect(first.summary.content.sourceSegmentIds).toContain(first.transcript[0].segmentId)
  expect(first.summary.content.decisions).toEqual([])
  expect(first.summary.content.actionItems).toEqual([])
  await page.getByRole('button', { name: '사전 교정 후보 확인', exact: true }).click()
  await expect(page.getByText('배포 → 릴리스', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '발언 수정', exact: true }).click()
  const staleDraft = '이전 버전에서 편집하던 문장입니다.'
  await page.getByLabel('표시 문장 수정').fill(staleDraft)
  await page.getByRole('button', { name: '검토 후 적용', exact: true }).click()
  await expect(page.locator('article').filter({ hasText: '표시 교정' }).getByText('이번 주 릴리스 일정을 확정하겠습니다.', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '수정 저장', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('revision is stale')
  await expect(page.getByLabel('표시 문장 수정')).toHaveValue(staleDraft)
  expect(await page.evaluate(async (id) => (await (await fetch(`/api/v1/meetings/${id}/transcript`)).json())[0].displayText, id)).toBe('이번 주 릴리스 일정을 확정하겠습니다.')
  await page.getByRole('button', { name: '취소', exact: true }).click()
  await page.getByRole('button', { name: '발언 수정', exact: true }).click()
  const edited = '이번 주 배포 일정을 목요일로 확정하겠습니다.'
  await page.getByLabel('표시 문장 수정').fill(edited)
  await page.getByRole('button', { name: '수정 저장', exact: true }).click()
  await expect(page.getByText('사용자 수정 보호됨', { exact: true })).toBeVisible()
  await expect(page.getByText('발언이 수정되어 요약 갱신이 필요합니다.', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '가상 요약 다시 생성', exact: true }).click()
  await expect(summary.getByText('MOCK · 요약 버전 2', { exact: true })).toBeVisible()
  await expect(page.getByText('발언이 수정되어 요약 갱신이 필요합니다.', { exact: true })).toHaveCount(0)
  await page.reload()
  await expect(summary.getByText('MOCK · 요약 버전 2', { exact: true })).toBeVisible()
  const after = await page.evaluate(async (id) => (await (await fetch(`/api/v1/meetings/${id}/transcript`)).json())[0], id)
  expect(after.originalText).toBe(first.transcript[0].originalText)
  expect(after.displayText).toBe(edited)
  expect(after.userLocked).toBe(true)
})
