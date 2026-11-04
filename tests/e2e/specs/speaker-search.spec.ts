import { expect, test } from 'playwright/test'

for (const width of [1440, 390]) {
  test(`speaker merge, per-utterance correction and search at ${width}px (synthetic only)`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    const names = [
      { label: 'speaker_0', displayName: '김정규', mergedInto: null as string | null },
      { label: 'speaker_1', displayName: '담당자', mergedInto: null as string | null },
      { label: 'speaker_2', displayName: '참여자', mergedInto: null as string | null },
    ]
    let overrides: {segmentId: string; label: string}[] = []
    const segments = ['speaker_0', 'speaker_1', 'speaker_2'].map((label, index) => ({ segmentId: `s${index + 1}`, eventId: `e${index + 1}`, sequence: index + 1,
      startMs: index * 1000, endMs: index * 1000 + 900, originalText: `Original ${index + 1}`, displayText: `급여 검증 ${index + 1}`,
      transcriptStatus: 'FINAL', speakerLabel: label, speakerStatus: 'TENTATIVE', revision: 0 }))
    const meeting = { id: 'synthetic', title: '합성 회의', status: 'STARTED', inputSource: 'MOCK', revision: 0 }
    let globalQuery = ''
    await page.route('**/api/v1/**', async route => {
      const url = new URL(route.request().url())
      const path = url.pathname
      let body: unknown = {}
      if (path.endsWith('/speaker-merge')) {
        const data = route.request().postDataJSON()
        const source = names.find(item => item.label === data.label)!
        const target = names.find(item => item.label === data.target)
        const root = target?.mergedInto ?? target?.label ?? null
        if (!target) source.mergedInto = null
        else { const sourceRoot = source.mergedInto ?? source.label; names.filter(item => item.label === sourceRoot || item.mergedInto === sourceRoot).forEach(item => { item.mergedInto = root }) }
        body = names
      } else if (path.endsWith('/speakers')) body = names
      else if (path.endsWith('/speaker-overrides')) body = overrides
      else if (/\/segments\/[^/]+\/speaker$/.test(path)) {
        const data = route.request().postDataJSON()
        const id = path.split('/').at(-2)!
        overrides = overrides.filter(item => item.segmentId !== id)
        if (data.label) overrides.push({ segmentId: id, label: data.label })
        body = overrides
      } else if (path.endsWith('/transcript')) body = segments
      else if (path.endsWith('/summary')) return route.fulfill({status: 404, json: {message:'No summary'}})
      else if (path.endsWith('/ai/configuration')) body = {enabled:true,llmAvailable:true,llmProvider:'CODEX'}
      else if (path.endsWith('/meetings/synthetic')) body = meeting
      else if (path.endsWith('/meetings')) { globalQuery = url.searchParams.get('q') ?? ''; body = [meeting] }
      await route.fulfill({json:body})
    })
    let send: ((message: string) => void) | undefined
    await page.routeWebSocket(/\/api\/v1\/meetings\/synthetic\/live/, socket => { send = message => socket.send(message) })
    await page.goto('/meetings/synthetic/live')
    const panel = page.locator('details').filter({has:page.locator('summary', {hasText:'화자 이름 설정'})}).first()
    await panel.getByRole('combobox',{name:'화자 3 합칠 대상',exact:true}).selectOption('speaker_0')
    await panel.getByRole('button',{name:'화자 합치기',exact:true}).nth(2).click()
    await expect(page.locator('#segment-s3').getByText('김정규',{exact:true})).toBeVisible()
    await expect.poll(() => Boolean(send)).toBe(true)
    send!(JSON.stringify({type:'transcript.final',meetingId:'synthetic',segmentId:'s4',eventId:'e4',sequence:4,startMs:4000,endMs:4900,text:'새 발언',speakerLabel:'speaker_2',speakerStatus:'TENTATIVE'}))
    await expect(page.locator('#segment-s4').getByText('김정규',{exact:true})).toBeVisible()
    await page.reload()
    await expect(page.locator('#segment-s3').getByText('김정규',{exact:true})).toBeVisible()
    await panel.getByRole('button',{name:'이 화자 분리하기'}).click()
    await expect(page.locator('#segment-s3').getByText('참여자',{exact:true})).toBeVisible()

    const correction = page.locator('#segment-s2').locator('..').locator('details').last()
    await correction.locator('summary').click()
    await correction.getByRole('combobox').selectOption('speaker_0')
    await expect(page.locator('#segment-s2').getByText('김정규',{exact:true})).toBeVisible()
    await page.reload()
    await expect(page.locator('#segment-s2').getByText('김정규',{exact:true})).toBeVisible()
    const search = page.getByRole('search',{name:'이 회의 발언 검색'})
    await search.getByRole('searchbox').fill('김정규')
    await expect(search.getByRole('status')).toHaveText('0 / 2개 일치')
    await search.getByRole('button',{name:'다음 결과'}).click()
    await expect(page.locator('#segment-s1')).toBeFocused()
    await search.getByRole('button',{name:'다음 결과'}).click()
    await expect(page.locator('#segment-s2')).toBeFocused()
    await search.getByRole('searchbox').fill('Original 3')
    await search.getByRole('button',{name:'다음 결과'}).click()
    await expect(page.locator('#segment-s3')).toBeFocused()
    await search.getByRole('searchbox').fill('없는 검색어')
    await expect(search.getByRole('status')).toHaveText('검색 결과가 없습니다')
    const restored = page.locator('#segment-s2').locator('..').locator('details').last()
    await restored.locator('summary').click()
    await restored.getByRole('combobox').selectOption('')
    await expect(page.locator('#segment-s2').getByText('담당자',{exact:true})).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)

    await page.goto('/meetings')
    await page.getByPlaceholder('제목·원문·교정문·화자 이름 검색').fill('김정규')
    await page.getByRole('button',{name:'검색',exact:true}).click()
    await expect.poll(() => globalQuery).toBe('김정규')
    await expect(page.getByText('합성 회의',{exact:true})).toBeVisible()
  })
}
