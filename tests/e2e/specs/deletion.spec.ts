import { expect, test } from '../fixtures'

test('deletes only the confirmed stopped meeting and its local recovery data', async ({ page }) => {
  await page.goto('/meetings')
  const title = `Synthetic deletion ${Date.now()}`
  const id = await page.evaluate(async (title) => {
    const response = await fetch('/api/v1/meetings', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title, inputSource: 'LOCAL_RECORDING' }) })
    if (!response.ok) throw new Error('Cannot create synthetic deletion meeting')
    const meeting = await response.json()
    const started = await fetch(`/api/v1/meetings/${meeting.id}/start`, { method: 'POST' })
    if (!started.ok) throw new Error('Cannot start synthetic deletion meeting')
    return meeting.id as string
  }, title)
  await page.goto(`/meetings/${id}/live`)
  await expect(page.getByRole('button', { name: '회의 삭제', exact: true })).toBeDisabled()
  await page.evaluate(async (id) => {
    const stopped = await fetch(`/api/v1/meetings/${id}/stop`, { method: 'POST' })
    if (!stopped.ok) throw new Error('Cannot stop synthetic deletion meeting')
  }, id)
  await page.reload()
  await expect(page.getByRole('button', { name: '회의 삭제', exact: true })).toBeEnabled()
  // Start from a clean loaded panel, then seed an interrupted recording for deletion.
  await page.evaluate(async (id) => {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('moa-recordings', 1)
      request.onupgradeneeded = () => {
        request.result.createObjectStore('sessions', { keyPath: 'meetingId' })
        request.result.createObjectStore('chunks', { keyPath: ['meetingId', 'sequence'] }).createIndex('meetingId', 'meetingId')
      }
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(['sessions', 'chunks'], 'readwrite')
      for (const key of [id, 'unrelated-synthetic-meeting']) {
        tx.objectStore('sessions').put({ meetingId: key, mimeType: 'audio/webm', chunkCount: 1, durationMs: 2000, stopped: true })
        tx.objectStore('chunks').put({ meetingId: key, sequence: 0, startMs: 0, endMs: 2000, sha256: '0'.repeat(64), blob: new Blob(['synthetic']) })
      }
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
    })
    db.close()
  }, id)
  await page.getByRole('button', { name: '회의 삭제', exact: true }).click()
  await expect(page.getByRole('button', { name: '영구 삭제', exact: true })).toBeDisabled()
  await page.getByLabel(`삭제할 회의 제목 입력: ${title}`, { exact: true }).fill(title)
  const changedTitle = `${title} 변경`
  await page.evaluate(async ({ id, title }) => {
    const meeting = await (await fetch(`/api/v1/meetings/${id}`)).json()
    const response = await fetch(`/api/v1/meetings/${id}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title, revision: meeting.revision }) })
    if (!response.ok) throw new Error('Cannot change synthetic deletion title')
  }, { id, title: changedTitle })
  await page.getByRole('button', { name: '영구 삭제', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('회의가 변경되었습니다.')
  await expect(page.getByLabel(`삭제할 회의 제목 입력: ${title}`, { exact: true })).toHaveValue(title)
  const preserved = await page.evaluate(async (id) => {
    const db = await new Promise<IDBDatabase>((resolve) => { const request = indexedDB.open('moa-recordings', 1); request.onsuccess = () => resolve(request.result) })
    const count = db.transaction('chunks').objectStore('chunks').index('meetingId').count(id)
    const value = await new Promise<number>((resolve) => { count.onsuccess = () => resolve(count.result) })
    db.close()
    return value
  }, id)
  expect(preserved).toBe(1)
  await page.reload()
  await page.getByRole('button', { name: '회의 삭제', exact: true }).click()
  await page.getByLabel(`삭제할 회의 제목 입력: ${changedTitle}`, { exact: true }).fill(changedTitle)
  await page.getByRole('button', { name: '영구 삭제', exact: true }).click()
  await expect(page).toHaveURL('/meetings')
  const result = await page.evaluate(async (id) => {
    const db = await new Promise<IDBDatabase>((resolve) => { const request = indexedDB.open('moa-recordings', 1); request.onsuccess = () => resolve(request.result) })
    const tx = db.transaction(['sessions', 'chunks'], 'readonly')
    const count = (request: IDBRequest<number>) => new Promise<number>((resolve) => { request.onsuccess = () => resolve(request.result) })
    const own = await Promise.all([count(tx.objectStore('sessions').count(id)), count(tx.objectStore('chunks').index('meetingId').count(id)), count(tx.objectStore('chunks').index('meetingId').count('unrelated-synthetic-meeting'))])
    db.close()
    return { own, status: (await fetch(`/api/v1/meetings/${id}`)).status }
  }, id)
  expect(result).toEqual({ own: [0, 0, 1], status: 404 })
})
