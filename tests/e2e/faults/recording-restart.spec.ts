import { execFileSync, spawn, type ChildProcess } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { randomUUID } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { expect, test } from 'playwright/test'

const enabled = process.env.OFFLINE_RESTART_TEST_ENABLED === 'true'
const quotaEnabled = process.env.OFFLINE_BROWSER_QUOTA_TEST_ENABLED === 'true'
const projectRoot = resolve(fileURLToPath(new URL('.', import.meta.url)), '../../..')
const apiPort = 18082
const webPort = 5182
const webOrigin = `http://127.0.0.1:${webPort}`
const apiOrigin = `http://127.0.0.1:${apiPort}`
const ownerToken = 'offline-restart-owner-token-000000000000'
const ownerUsername = 'owner'
const ownerPassword = 'synthetic-owner-password-2026'
const psql = join(projectRoot, '.tools/postgres/pgsql/bin/psql.exe')
const jar = join(projectRoot, 'apps/api/target/api-0.0.1-SNAPSHOT.jar')
const vite = join(projectRoot, 'apps/web/node_modules/vite/bin/vite.js')
const database = `ai_meeting_restart_${randomUUID().replaceAll('-', '').slice(0, 16)}`
const recordingTempBase = resolve(projectRoot, '.tools/tmp')
const processes = new Set<ChildProcess>()
const logs = new Map<ChildProcess, string[]>()
let databaseCreated = false
let recordingRoot: string | undefined
let api: ChildProcess | undefined
let web: ChildProcess | undefined

function capture(child: ChildProcess) {
  const lines: string[] = []
  logs.set(child, lines)
  const append = (data: Buffer) => {
    lines.push(...data.toString('utf8').split(/\r?\n/).filter(Boolean))
    if (lines.length > 80) lines.splice(0, lines.length - 80)
  }
  child.stdout?.on('data', append)
  child.stderr?.on('data', append)
  processes.add(child)
  child.once('exit', () => processes.delete(child))
  return child
}

async function waitFor(url: string, label: string, child: ChildProcess, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (child.exitCode !== null || child.signalCode !== null) throw new Error(`${label} exited (${child.exitCode ?? child.signalCode})\n${(logs.get(child) ?? []).slice(-20).join('\n')}`)
    try {
      const response = await fetch(url)
      if (response.ok) return
    } catch { /* process is still starting */ }
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  throw new Error(`${label} was not ready within ${timeoutMs}ms\n${(logs.get(child) ?? []).slice(-20).join('\n')}`)
}

async function stopOwned(child: ChildProcess | undefined) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return
  const exited = new Promise<void>((resolveExit) => child.once('exit', () => resolveExit()))
  child.kill()
  await Promise.race([exited, new Promise<void>((_, reject) => setTimeout(() => reject(new Error(`Owned process ${child.pid} did not exit`)), 10_000))])
}

function psqlCommand(sql: string) {
  return execFileSync(psql, ['-h', '127.0.0.1', '-p', '55432', '-U', 'ai_meeting', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '-c', sql], {
    cwd: projectRoot,
    env: { ...process.env, PGPASSWORD: '' },
    encoding: 'utf8',
    timeout: 15_000,
  })
}

function startApi() {
  if (!recordingRoot) throw new Error('Recording root was not initialized')
  const child = capture(spawn('java', ['-jar', jar], {
    cwd: projectRoot,
    env: {
      ...process.env,
      SERVER_ADDRESS: '127.0.0.1',
      SERVER_PORT: String(apiPort),
      SERVER_FORWARD_HEADERS_STRATEGY: 'none',
      DATABASE_URL: `jdbc:postgresql://127.0.0.1:55432/${database}`,
      DATABASE_USER: 'ai_meeting',
      DATABASE_PASSWORD: '',
      APP_OWNER_TOKEN: ownerToken,
      APP_ALLOWED_ORIGINS: webOrigin,
      APP_RECORDING_ROOT: recordingRoot,
      APP_FAKE_EVENT_DELAY_MS: '500',
      PAID_API_TESTS_ENABLED: 'false',
    },
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  }))
  api = child
  return child
}

function startWeb() {
  const child = capture(spawn(process.execPath, [vite, 'preview', '--host', '127.0.0.1', '--port', String(webPort), '--strictPort'], {
    cwd: join(projectRoot, 'apps/web'),
    env: { ...process.env, API_PROXY_TARGET: apiOrigin },
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  }))
  web = child
  return child
}

async function indexedRecording(page: import('playwright/test').Page, meetingId: string) {
  return page.evaluate(async (id) => {
    const db = await new Promise<IDBDatabase>((resolveDb, reject) => {
      const request = indexedDB.open('moa-recordings', 1)
      request.onsuccess = () => resolveDb(request.result)
      request.onerror = () => reject(request.error)
    })
    const tx = db.transaction(['sessions', 'chunks'], 'readonly')
    const read = <T>(request: IDBRequest<T>) => new Promise<T>((resolveValue, reject) => {
      request.onsuccess = () => resolveValue(request.result)
      request.onerror = () => reject(request.error)
    })
    const values = await Promise.all([
      read(tx.objectStore('sessions').count(id)),
      read(tx.objectStore('chunks').index('meetingId').count(id)),
      read(tx.objectStore('sessions').get(id)),
    ])
    db.close()
    const session = values[2] as { chunkCount?: number; durationMs?: number } | undefined
    return { sessions: values[0], pending: values[1], chunkCount: session?.chunkCount ?? 0, durationMs: session?.durationMs ?? 0 }
  }, meetingId)
}

async function loginOwner(page: import('playwright/test').Page) {
  const status = await page.evaluate(async ({ token, username, password }) => {
    const configuration = await fetch('/api/v1/session/configuration').then((response) => response.json()) as { setupRequired: boolean }
    const response = await fetch(configuration.setupRequired ? '/api/v1/session/setup' : '/api/v1/session', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(configuration.setupRequired
        ? { token, username, password, rememberMe: true }
        : { username, password, rememberMe: true }),
    })
    return response.status
  }, { token: ownerToken, username: ownerUsername, password: ownerPassword })
  expect(status).toBe(200)
}

test.beforeAll(async () => {
  expect(enabled).toBeTruthy()
  expect(database).toMatch(/^ai_meeting_restart_[0-9a-f]{16}$/)
  recordingRoot = mkdtempSync(join(recordingTempBase, 'ai-meeting-restart-'))
  psqlCommand(`CREATE DATABASE "${database}"`)
  databaseCreated = true
  await waitFor(`${apiOrigin}/actuator/health`, 'API', startApi())
  await waitFor(webOrigin, 'Vite preview', startWeb())
})

test.afterAll(async () => {
  const failures: string[] = []
  for (const child of [...processes]) {
    try { await stopOwned(child) } catch (error) { failures.push(String(error)) }
  }
  if (databaseCreated && /^ai_meeting_restart_[0-9a-f]{16}$/.test(database)) {
    try { psqlCommand(`DROP DATABASE IF EXISTS "${database}" WITH (FORCE)`) } catch (error) { failures.push(`Database cleanup: ${String(error)}`) }
  }
  const ownedRoot = recordingRoot && resolve(recordingRoot)
  if (ownedRoot && ownedRoot.startsWith(`${recordingTempBase}\\`) && ownedRoot.split(/[\\/]/).at(-1)?.startsWith('ai-meeting-restart-')) {
    try { rmSync(ownedRoot, { recursive: true, force: true }) } catch (error) { failures.push(`Recording cleanup: ${String(error)}`) }
  } else if (ownedRoot) failures.push(`Refused ambiguous recording cleanup: ${ownedRoot}`)
  if (failures.length) throw new Error(failures.join('\n'))
})

test('keeps browser audio through an owned API crash and completes after restart', async ({ page }, testInfo) => {
  const sockets: string[] = []
  page.on('websocket', (socket) => sockets.push(socket.url()))
  await page.goto('/meetings')
  await loginOwner(page)

  const meeting = await page.evaluate(async () => {
    const create = await fetch('/api/v1/meetings', {
      method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: `Restart recording ${Date.now()}`, inputSource: 'LOCAL_RECORDING', topicId: null }),
    })
    if (!create.ok) throw new Error(`Create meeting: ${create.status}`)
    const created = await create.json()
    const start = await fetch(`/api/v1/meetings/${created.id}/start`, { method: 'POST', credentials: 'include' })
    if (!start.ok) throw new Error(`Start meeting: ${start.status}`)
    return start.json()
  }) as { id: string }

  await page.goto(`/meetings/${meeting.id}/live`)
  await page.getByLabel('참여자에게 녹음을 알렸고 녹음에 동의합니다.').check()
  await page.getByRole('button', { name: '마이크 녹음 시작' }).click()
  await expect(page.getByRole('button', { name: '녹음 종료 및 저장' })).toBeVisible()
  const startedAt = await page.evaluate(() => performance.now())
  await expect.poll(async () => {
    const stored = await indexedRecording(page, meeting.id)
    return stored.chunkCount >= 1 && stored.pending === 0
  }, { timeout: 15_000 }).toBeTruthy()

  const firstApi = api
  expect(firstApi).toBeTruthy()
  await stopOwned(firstApi)
  expect(firstApi!.exitCode !== null || firstApi!.signalCode !== null).toBeTruthy()

  await expect.poll(async () => (await indexedRecording(page, meeting.id)).pending, { timeout: 12_000 }).toBeGreaterThanOrEqual(1)
  const pendingDuringOutage = await indexedRecording(page, meeting.id)

  await waitFor(`${apiOrigin}/actuator/health`, 'restarted API', startApi())
  const retainedSessionStatus = await page.evaluate(async (id) => (await fetch(`/api/v1/meetings/${id}`, { credentials: 'include' })).status, meeting.id)
  expect(retainedSessionStatus).toBe(200)
  await expect.poll(async () => page.evaluate(async (id) => (await fetch(`/api/v1/meetings/${id}`, { credentials: 'include' })).status, meeting.id)).toBe(200)
  await expect.poll(async () => (await indexedRecording(page, meeting.id)).pending, { timeout: 15_000 }).toBe(0)

  await page.waitForTimeout(2_500)
  const elapsedBeforeStop = await page.evaluate((start) => performance.now() - start, startedAt)
  await page.getByRole('button', { name: '녹음 종료 및 저장' }).click()
  await expect(page.getByText('서버 저장 완료', { exact: false })).toBeVisible({ timeout: 45_000 })

  const result = await page.evaluate(async (id) => {
    async function json(path: string) {
      const response = await fetch(`/api/v1/meetings/${id}${path}`, { credentials: 'include' })
      if (!response.ok) throw new Error(`${path || 'meeting'}: ${response.status}`)
      return response.json()
    }
    const audio = await fetch(`/api/v1/meetings/${id}/recording/audio`, { credentials: 'include' })
    if (!audio.ok) throw new Error(`audio: ${audio.status}`)
    const buffer = await audio.arrayBuffer()
    const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', buffer)), (value) => value.toString(16).padStart(2, '0')).join('')
    return { metadata: await json('/recording'), meeting: await json(''), transcript: await json('/transcript'), bytes: buffer.byteLength, digest }
  }, meeting.id)
  const browserStorage = await indexedRecording(page, meeting.id)

  expect(result.metadata.state).toBe('COMPLETED')
  expect(result.metadata.sha256).toBe(result.digest)
  expect(result.metadata.bytes).toBe(result.bytes)
  expect(result.metadata.chunkCount).toBeGreaterThanOrEqual(3)
  expect(result.metadata.durationMs).toBeGreaterThanOrEqual(elapsedBeforeStop - 3_000)
  expect(result.bytes).toBeGreaterThan(100)
  expect(result.meeting.status).toBe('STOPPED')
  expect(result.transcript).toEqual([])
  expect(browserStorage.sessions).toBe(0)
  expect(browserStorage.pending).toBe(0)
  expect(sockets).toEqual([])
  await page.locator('audio').evaluate((element: HTMLAudioElement) => element.play())
  await expect.poll(() => page.locator('audio').evaluate((element: HTMLAudioElement) => element.currentTime)).toBeGreaterThan(0.1)
  await testInfo.attach('restart-recording-metrics', {
    body: JSON.stringify({ retainedSessionStatus, pendingDuringOutage, elapsedBeforeStop, metadata: result.metadata, browserStorage, sockets }, null, 2),
    contentType: 'application/json',
  })
})

test('stops safely on the browser quota boundary and recovers acknowledged audio', async ({ page, context }, testInfo) => {
  test.skip(!quotaEnabled, 'Set OFFLINE_BROWSER_QUOTA_TEST_ENABLED=true to run the Chrome quota-policy check.')
  const sockets: string[] = []
  page.on('websocket', (socket) => sockets.push(socket.url()))
  await page.goto('/meetings')
  await loginOwner(page)

  const meeting = await page.evaluate(async () => {
    const create = await fetch('/api/v1/meetings', {
      method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: `Quota recording ${Date.now()}`, inputSource: 'LOCAL_RECORDING', topicId: null }),
    })
    if (!create.ok) throw new Error(`Create meeting: ${create.status}`)
    const created = await create.json()
    const start = await fetch(`/api/v1/meetings/${created.id}/start`, { method: 'POST', credentials: 'include' })
    if (!start.ok) throw new Error(`Start meeting: ${start.status}`)
    return start.json()
  }) as { id: string }

  await page.goto(`/meetings/${meeting.id}/live`)
  await page.getByLabel('참여자에게 녹음을 알렸고 녹음에 동의합니다.').check()
  await page.getByRole('button', { name: '마이크 녹음 시작' }).click()
  await expect(page.getByRole('button', { name: '녹음 종료 및 저장' })).toBeVisible()
  // A local chunk is removed only after the upload endpoint acknowledges it. The
  // in-progress server metadata exposes its final chunk count only at completion.
  await expect.poll(async () => {
    const stored = await indexedRecording(page, meeting.id)
    return stored.chunkCount >= 1 && stored.pending === 0
  }, { timeout: 15_000 }).toBeTruthy()

  const cdp = await context.newCDPSession(page)
  const origin = new URL(page.url()).origin
  let quotaOverridden = false
  let quotaBefore: Awaited<ReturnType<typeof cdp.send<'Storage.getUsageAndQuota'>>> | undefined
  let quotaDuring: Awaited<ReturnType<typeof cdp.send<'Storage.getUsageAndQuota'>>> | undefined
  try {
    quotaBefore = await cdp.send('Storage.getUsageAndQuota', { origin })
    expect(quotaBefore.usage).toBeGreaterThan(1)
    await cdp.send('Storage.overrideQuotaForOrigin', { origin, quotaSize: 1 })
    quotaOverridden = true
    quotaDuring = await cdp.send('Storage.getUsageAndQuota', { origin })
    expect(quotaDuring.overrideActive).toBe(true)
    expect(quotaDuring.quota).toBe(1)
    const rendererQuota = await page.evaluate(() => navigator.storage.estimate())
    const quotaProbe = await page.evaluate(async () => {
      const db = await new Promise<IDBDatabase>((resolveDb, reject) => {
        const request = indexedDB.open('moa-quota-probe', 1)
        request.onupgradeneeded = () => request.result.createObjectStore('probe')
        request.onsuccess = () => resolveDb(request.result)
        request.onerror = () => reject(request.error)
      })
      try {
        const payload = new Uint8Array(16 * 1024 * 1024)
        for (let offset = 0; offset < payload.length; offset += 65_536) {
          crypto.getRandomValues(payload.subarray(offset, Math.min(offset + 65_536, payload.length)))
        }
        return await new Promise<string>((resolveResult) => {
          const tx = db.transaction('probe', 'readwrite')
          tx.objectStore('probe').put(new Blob([payload]), 'oversized')
          tx.oncomplete = () => resolveResult('accepted')
          tx.onabort = () => resolveResult(tx.error?.name ?? 'aborted')
        })
      } finally {
        db.close()
        indexedDB.deleteDatabase('moa-quota-probe')
      }
    })
    test.skip(quotaProbe === 'accepted', `Chrome reported an active 1-byte CDP quota but accepted a 16 MiB IndexedDB write (navigator quota ${rendererQuota.quota ?? 'unknown'} bytes); this browser cannot validate the quota-failure path.`)
    expect(quotaProbe).toBe('QuotaExceededError')

    await expect(page.getByText('브라우저 저장 실패', { exact: false })).toBeVisible({ timeout: 12_000 })
    await expect(page.getByRole('button', { name: '녹음 종료 및 저장' })).toBeHidden({ timeout: 8_000 })
    const retained = await indexedRecording(page, meeting.id)
    expect(retained.sessions).toBe(1)
    expect(retained.chunkCount).toBeGreaterThanOrEqual(1)
    expect(retained.pending).toBe(0)

    await cdp.send('Storage.overrideQuotaForOrigin', { origin })
    quotaOverridden = false
    const quotaReset = await cdp.send('Storage.getUsageAndQuota', { origin })
    expect(quotaReset.overrideActive).toBe(false)
    expect(quotaReset.quota).toBeGreaterThan(1)

    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.getByRole('button', { name: '보관한 청크 복구' }).click()
    await expect(page.getByText('서버 저장 완료', { exact: false })).toBeVisible({ timeout: 30_000 })

    const result = await page.evaluate(async (id) => {
      async function json(path: string) {
        const response = await fetch(`/api/v1/meetings/${id}${path}`, { credentials: 'include' })
        if (!response.ok) throw new Error(`${path || 'meeting'}: ${response.status}`)
        return response.json()
      }
      const audio = await fetch(`/api/v1/meetings/${id}/recording/audio`, { credentials: 'include' })
      if (!audio.ok) throw new Error(`audio: ${audio.status}`)
      const buffer = await audio.arrayBuffer()
      const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', buffer)), (value) => value.toString(16).padStart(2, '0')).join('')
      return { metadata: await json('/recording'), meeting: await json(''), transcript: await json('/transcript'), bytes: buffer.byteLength, digest }
    }, meeting.id)
    const browserStorage = await indexedRecording(page, meeting.id)

    expect(result.metadata.state).toBe('COMPLETED')
    expect(result.metadata.sha256).toBe(result.digest)
    expect(result.metadata.bytes).toBe(result.bytes)
    expect(result.metadata.chunkCount).toBe(retained.chunkCount)
    expect(result.metadata.durationMs).toBe(retained.durationMs)
    expect(result.bytes).toBeGreaterThan(100)
    expect(result.meeting.status).toBe('STOPPED')
    expect(result.transcript).toEqual([])
    expect(browserStorage.sessions).toBe(0)
    expect(browserStorage.pending).toBe(0)
    expect(sockets).toEqual([])
    await page.locator('audio').evaluate((element: HTMLAudioElement) => element.play())
    await expect.poll(() => page.locator('audio').evaluate((element: HTMLAudioElement) => element.currentTime)).toBeGreaterThan(0.1)
    await testInfo.attach('quota-recording-metrics', {
      body: JSON.stringify({ quotaBefore, quotaDuring, retained, metadata: result.metadata, browserStorage, sockets }, null, 2),
      contentType: 'application/json',
    })
  } finally {
    if (quotaOverridden) await cdp.send('Storage.overrideQuotaForOrigin', { origin }).catch(() => {})
    await cdp.detach().catch(() => {})
  }
})
