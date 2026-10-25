import { expect, test } from 'playwright/test'

test('registers the owner once through the browser and switches to credential login', async ({ page }) => {
  const configuration = await page.request.get('/api/v1/session/configuration')
  expect(configuration.ok()).toBeTruthy()
  test.skip(!(await configuration.json()).setupRequired, 'The owner is already registered in this test database')

  let setupRequests = 0
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/session/setup') setupRequests++
  })
  await page.goto('/meetings')
  await expect(page.getByRole('heading', { name: '내 계정 만들기' })).toBeVisible()
  await page.getByLabel('접속 토큰', { exact: true }).fill(process.env.E2E_OWNER_TOKEN ?? 'phase0-e2e-owner-token-000000000000')
  await page.getByLabel('아이디', { exact: true }).fill('owner')
  await page.getByLabel('비밀번호', { exact: true }).fill('synthetic-owner-password-2026')
  await page.getByLabel('비밀번호 확인', { exact: true }).fill('synthetic-password-mismatch')
  await page.getByRole('button', { name: '계정 만들고 시작하기' }).click()
  await expect(page.getByRole('alert')).toHaveText('비밀번호가 일치하지 않습니다.')
  expect(setupRequests).toBe(0)

  await page.getByLabel('비밀번호 확인', { exact: true }).fill('synthetic-owner-password-2026')
  await page.getByRole('button', { name: '계정 만들고 시작하기' }).click()
  await expect(page.getByText('MOCK MODE')).toBeVisible()
  expect(setupRequests).toBe(1)
  expect((await page.request.get('/api/v1/session/configuration')).ok()).toBeTruthy()
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page.getByRole('heading', { name: '내 회의록에 접속' })).toBeVisible()
  await expect(page.getByLabel('접속 토큰', { exact: true })).toHaveCount(0)
  await expect(page.getByLabel('아이디', { exact: true })).toBeVisible()
})
