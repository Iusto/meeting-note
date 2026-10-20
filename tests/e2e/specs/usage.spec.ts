import { expect, test } from '../fixtures'

test('distinguishes unconfirmed charges from reserved budget and warns before exhaustion', async ({ page }) => {
  const snapshot = {
    month: new Date().toISOString().slice(0, 7), currency: 'USD', paidApiEnabled: false,
    recordedSeconds: 0, totalEstimatedUsd: 1.25, totalActualUsd: null, reservedUsd: 7,
    budgetCommittedUsd: 8.25, budgetRemainingUsd: 1.75, unreconciledCount: 2,
    budgetUsd: 10, warnAtPercent: 80, budgetState: 'WARNING', meetings: [],
  }
  await page.route('**/api/v1/usage?*', (route) => route.fulfill({ json: snapshot }))
  await page.goto('/usage')
  await expect(page.getByText('예약 포함 비용이 예산 경고 기준 80%에 도달했습니다.', { exact: true })).toBeVisible()
  await expect(page.getByText('요청 2건의 청구 금액이 미확인입니다.', { exact: false })).toBeVisible()
  await expect(page.locator('dl > div').filter({ has: page.getByText('청구 대조 금액', { exact: true }) }).locator('dd')).toHaveText('대조 전')
  await expect(page.locator('dl > div').filter({ has: page.getByText('예약 포함 예산 잔액', { exact: true }) }).locator('dd')).toHaveText('1.75 USD')
  snapshot.budgetState = 'EXHAUSTED'
  snapshot.budgetCommittedUsd = 12
  snapshot.budgetRemainingUsd = 0
  await page.reload()
  await expect(page.getByText('예약 포함 비용이 월 예산에 도달했습니다.', { exact: false })).toBeVisible()
  await expect(page.locator('dl > div').filter({ has: page.getByText('예약 포함 예산 잔액', { exact: true }) }).locator('dd')).toHaveText('0.00 USD')
})

test('persists budget settings, keeps paid APIs off and protects conflicting drafts', async ({ page }) => {
  await page.goto('/usage')
  await expect(page.getByRole('heading', { name: '사용량·예산', exact: true })).toBeVisible()
  await expect(page.getByText('외부 API 호출이 차단되어 있습니다.', { exact: false })).toBeVisible()
  const initial = await page.evaluate(async () => (await fetch('/api/v1/settings')).json())
  try {
    await page.getByLabel('월 예산 (USD)', { exact: true }).fill('12.50')
    await page.getByLabel('예산 경고 기준 (%)', { exact: true }).fill('75')
    await page.getByLabel('유료 작업 회당 최대 시간 (분)', { exact: true }).fill('15')
    await page.getByLabel('녹음 보관 검토 주기 (일)', { exact: true }).fill('30')
    await page.getByRole('button', { name: '설정 저장', exact: true }).click()
    await expect(page.getByRole('status')).toHaveText('설정을 저장했습니다. 유료 API는 계속 차단됩니다.')
    await page.reload()
    await expect(page.getByLabel('월 예산 (USD)', { exact: true })).toHaveValue('12.5')
    await expect(page.getByLabel('예산 경고 기준 (%)', { exact: true })).toHaveValue('75')
    await expect(page.getByLabel('녹음 보관 검토 주기 (일)', { exact: true })).toHaveValue('30')
    // Tiny synthetic bytes exercise duration accounting; playback is covered by recording.spec.ts.
    const fixtureId = await page.evaluate(async () => {
      const post = async (path: string, body?: unknown) => {
        const response = await fetch(`/api/v1${path}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
        if (!response.ok) throw new Error(`Synthetic usage fixture failed: ${response.status}`)
        return response.json()
      }
      const meeting = await post('/meetings', { title: `Synthetic usage ${Date.now()}`, inputSource: 'LOCAL_RECORDING' })
      await post(`/meetings/${meeting.id}/start`)
      await post(`/meetings/${meeting.id}/stop`)
      const content = new TextEncoder().encode('synthetic usage bytes')
      const digest = [...new Uint8Array(await crypto.subtle.digest('SHA-256', content))].map((value) => value.toString(16).padStart(2, '0')).join('')
      const query = new URLSearchParams({ sequence: '0', startMs: '0', endMs: '2000', sha256: digest, mimeType: 'audio/webm' })
      const uploaded = await fetch(`/api/v1/meetings/${meeting.id}/recording-chunks?${query}`, { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: content })
      if (!uploaded.ok) throw new Error('Synthetic usage chunk upload failed')
      await post(`/meetings/${meeting.id}/recording-complete`, { chunkCount: 1 })
      return meeting.id
    })
    const usage = await page.evaluate(async () => (await fetch('/api/v1/usage')).json())
    expect(usage).toMatchObject({ paidApiEnabled: false, currency: 'USD', budgetUsd: 12.5, totalEstimatedUsd: 0, totalActualUsd: 0 })
    expect(usage.recordedSeconds).toBeGreaterThanOrEqual(2)
    expect(usage.meetings.find((meeting: { meetingId: string }) => meeting.meetingId === fixtureId)).toMatchObject({ inputSource: 'LOCAL_RECORDING', recordedSeconds: 2, estimatedUsd: 0, actualUsd: 0 })
    await page.getByLabel('사용량 월 (UTC)', { exact: true }).fill('2000-01')
    await expect(page.getByText('이 달에 시작한 회의가 없습니다.', { exact: true })).toBeVisible()
    await page.getByLabel('월 예산 (USD)', { exact: true }).fill('14')
    await page.evaluate(async () => {
      const { paidApiEnabled: _, ...settings } = await (await fetch('/api/v1/settings')).json()
      const response = await fetch('/api/v1/settings', { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...settings, monthlyBudgetUsd: 13 }) })
      if (!response.ok) throw new Error('Synthetic concurrent settings update failed')
    })
    await page.getByRole('button', { name: '설정 저장', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText('다른 탭에서 설정을 변경했습니다.')
    await expect(page.getByLabel('월 예산 (USD)', { exact: true })).toHaveValue('14')
  } finally {
    await page.evaluate(async (initial) => {
      const latest = await (await fetch('/api/v1/settings')).json()
      const { paidApiEnabled: _, ...settings } = initial
      const response = await fetch('/api/v1/settings', { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...settings, revision: latest.revision }) })
      if (!response.ok) throw new Error('Cannot restore synthetic budget settings')
    }, initial)
  }
})
