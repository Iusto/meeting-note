import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, api } from '../api'
import { AppLink } from '../router'
import { usageApi } from '../usageApi'
import type { Settings, Usage } from '../usageApi'
import type { AiConfiguration } from '../types'

const usd = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 6 })

export function UsagePage({ onUnauthorized }: { onUnauthorized: () => void }) {
  const [month, setMonth] = useState(new Date().toISOString().slice(0, 7))
  const [usage, setUsage] = useState<Usage | null>(null)
  const [settings, setSettings] = useState<Settings | null>(null)
  const [budget, setBudget] = useState('')
  const [meetingBudget, setMeetingBudget] = useState('3')
  const [warn, setWarn] = useState('80')
  const [minutes, setMinutes] = useState('240')
  const [retention, setRetention] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [aiConfiguration, setAiConfiguration] = useState<AiConfiguration>()

  useEffect(() => {
    let active = true
    usageApi.settings().then((value) => {
      if (!active) return
      setMeetingBudget(String(value.perMeetingBudgetUsd)); setSettings(value); setBudget(String(value.monthlyBudgetUsd)); setWarn(String(value.warnAtPercent))
      setMinutes(String(value.perMeetingMaxMinutes)); setRetention(value.recordingRetentionDays === null ? '' : String(value.recordingRetentionDays))
    }).catch((caught) => {
      if (!active) return
      if (caught instanceof ApiError && caught.status === 401) onUnauthorized()
      else setError(caught instanceof Error ? caught.message : '설정을 불러오지 못했습니다.')
    })
    api.getAiConfiguration().then((value) => { if (active) setAiConfiguration(value) }).catch((caught) => {
      if (caught instanceof ApiError && caught.status === 401) onUnauthorized()
    })
    return () => { active = false }
  }, [onUnauthorized])

  useEffect(() => {
    let active = true
    setUsage(null); setError('')
    usageApi.usage(month).then((value) => { if (active) setUsage(value) }).catch((caught) => {
      if (!active) return
      if (caught instanceof ApiError && caught.status === 401) onUnauthorized()
      else setError(caught instanceof Error ? caught.message : '사용량을 불러오지 못했습니다.')
    })
    return () => { active = false }
  }, [month, onUnauthorized])

  async function save(event: FormEvent) {
    event.preventDefault()
    if (!settings) return
    setBusy(true); setError(''); setNotice('')
    try {
      const updated = await usageApi.saveSettings({ monthlyBudgetUsd: Number(budget), perMeetingBudgetUsd: Number(meetingBudget), warnAtPercent: Number(warn), perMeetingMaxMinutes: Number(minutes), recordingRetentionDays: retention === '' ? null : Number(retention), revision: settings.revision })
      setSettings(updated)
      setAiConfiguration(await api.getAiConfiguration())
      setNotice(`설정을 저장했습니다.${aiConfiguration?.enabled ? ' 새 유료 요청부터 변경한 상한을 적용합니다.' : ' 유료 API는 계속 차단됩니다.'}`)
      setUsage(await usageApi.usage(month))
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) onUnauthorized()
      else setError(caught instanceof ApiError && caught.status === 409 ? '다른 탭에서 설정을 변경했습니다. 초안을 보관하고 새로고침하세요.' : caught instanceof Error ? caught.message : '설정을 저장하지 못했습니다.')
    } finally { setBusy(false) }
  }

  return <main className="mx-auto max-w-5xl px-5 py-10 md:px-10">
    <p className="eyebrow">개인 작업 공간</p><h1 className="mt-3 text-3xl font-black">사용량·예산</h1>
    <p className="mt-4 rounded-2xl bg-mint p-4 text-sm leading-6 text-moss">{aiConfiguration?.enabled ? '유료 AI가 활성화되어 있습니다. LIVE 전사와 AI 요약·교정·용어 후보 요청은 아래 예산과 서버 상한 안에서 비용 원장에 기록됩니다.' : '외부 API 호출이 차단되어 있습니다. 가상 전사·요약과 로컬 녹음의 외부 API 비용은 0 USD입니다.'} 서버·저장 공간 비용은 포함하지 않습니다.</p>
    {error && <p role="alert" className="mt-4 text-sm text-red-700">{error}</p>}
    {notice && <p role="status" className="mt-4 text-sm text-moss">{notice}</p>}
    <section className="mt-6 rounded-2xl border border-moss/10 bg-white p-6" aria-label="월별 사용량">
      <label className="block text-sm font-bold" htmlFor="usage-month">사용량 월 (UTC)</label>
      <input id="usage-month" type="month" required className="field mt-2 max-w-xs" value={month} onChange={(event) => { if (event.target.value) setMonth(event.target.value) }} />
      {usage ? <>
        <dl className="mt-5 grid gap-4 sm:grid-cols-3">
          <div><dt className="text-sm text-slate-500">완료 녹음</dt><dd className="mt-1 text-2xl font-black">{(usage.recordedSeconds / 60).toFixed(1)}분</dd></div>
          <div><dt className="text-sm text-slate-500">확인된 사용량 추정 비용</dt><dd className="mt-1 text-2xl font-black">{usd.format(usage.totalEstimatedUsd)} USD</dd></div>
          <div><dt className="text-sm text-slate-500">처리 중·과금 미확인 예약액</dt><dd className="mt-1 text-2xl font-black">{usd.format(usage.reservedUsd)} USD</dd></div>
          <div><dt className="text-sm text-slate-500">월 예산</dt><dd className="mt-1 text-2xl font-black">{usd.format(usage.budgetUsd)} USD</dd></div>
          <div><dt className="text-sm text-slate-500">예약 포함 예산 잔액</dt><dd className="mt-1 text-2xl font-black">{usd.format(usage.budgetRemainingUsd)} USD</dd></div>
          <div><dt className="text-sm text-slate-500">청구 대조 금액</dt><dd className="mt-1 text-2xl font-black">{usage.totalActualUsd === null ? '대조 전' : `${usd.format(usage.totalActualUsd)} USD`}</dd></div>
        </dl>
        {usage.budgetState === 'DISABLED' && <p className="mt-3 text-sm text-amber-800">예산이 0으로 설정되어 있어 유료 작업 예산이 없습니다.</p>}
        {usage.budgetState === 'WARNING' && <p role="status" className="mt-3 text-sm text-amber-800">예약 포함 비용이 예산 경고 기준 {usage.warnAtPercent}%에 도달했습니다.</p>}
        {usage.budgetState === 'EXHAUSTED' && <p role="status" className="mt-3 text-sm text-red-700">예약 포함 비용이 월 예산에 도달했습니다. 새 유료 작업을 예약할 수 없습니다.</p>}
        {usage.unreconciledCount > 0 && <p className="mt-3 text-sm text-amber-800">요청 {usage.unreconciledCount}건의 청구 금액이 미확인입니다. 추정 비용은 실제 청구액과 다를 수 있습니다.</p>}
        <p className="mt-3 text-xs leading-5 text-slate-500">완료 녹음은 회의 시작 월(미시작 회의는 생성 월), API 비용은 요청 예약 월(UTC)에 합산합니다. 월 비용에는 삭제된 회의의 기록도 포함합니다. 응답이 끊겨 과금 여부를 모르면 예약액을 유지합니다. 청구 대조는 실제 API 연동 후 검증합니다.</p>
        <div className="mt-5 space-y-3">{usage.meetings.length === 0 ? <p className="text-sm text-slate-500">이 달에 시작한 회의가 없습니다.</p> : usage.meetings.map((meeting) => <div key={meeting.meetingId} className="flex flex-wrap items-center justify-between gap-2 border-t border-moss/10 pt-3 text-sm">
          <AppLink className="font-bold text-moss underline" href={`/meetings/${meeting.meetingId}/live`}>{meeting.title}</AppLink>
          {meeting.retentionReviewDue && <span className="rounded bg-amber-100 px-2 py-1 text-xs font-bold text-amber-900">보관 검토 대상</span>}
          <span>{meeting.inputSource === 'MOCK' ? '가상 회의' : meeting.inputSource === 'LIVE' ? '실제 AI + 원본 녹음' : '로컬 녹음'} · {(meeting.recordedSeconds / 60).toFixed(1)}분 · 완료 파일 {(meeting.recordingBytes / 1024 / 1024).toFixed(2)} MiB · 추정 {usd.format(meeting.estimatedUsd)} USD{meeting.reservedUsd > 0 && ` · 예약 ${usd.format(meeting.reservedUsd)} USD`}</span>
        </div>)}</div>
      </> : <p className="mt-4 text-sm text-slate-500">사용량을 불러오는 중…</p>}
    </section>
    <section className="mt-6 rounded-2xl border border-moss/10 bg-white p-6" aria-label="예산 및 보관 설정">
      <h2 className="text-xl font-black">예산·보관 설정</h2>
      <form className="mt-5 grid gap-4 sm:grid-cols-2" onSubmit={(event) => void save(event)}>
        <label className="text-sm font-bold">월 예산 (USD)<input type="number" className="field mt-2" required min="0" max="10000" step="0.01" disabled={!settings || busy} value={budget} onChange={(event) => setBudget(event.target.value)} /></label>
        <label className="text-sm font-bold">회의당 최대 예산 (USD)<input type="number" className="field mt-2" required min="0.01" max="10000" step="0.01" disabled={!settings || busy} value={meetingBudget} onChange={(event) => setMeetingBudget(event.target.value)} /></label>
        <label className="text-sm font-bold">예산 경고 기준 (%)<input type="number" className="field mt-2" required min="1" max="100" step="1" disabled={!settings || busy} value={warn} onChange={(event) => setWarn(event.target.value)} /></label>
        <label className="text-sm font-bold">유료 작업 회당 최대 시간 (분)<input type="number" className="field mt-2" required min="1" max="1440" step="1" disabled={!settings || busy} value={minutes} onChange={(event) => setMinutes(event.target.value)} /></label>
        <label className="text-sm font-bold">녹음 보관 검토 주기 (일)<input type="number" className="field mt-2" min="1" max="3650" step="1" placeholder="제한 없음" disabled={!settings || busy} value={retention} onChange={(event) => setRetention(event.target.value)} /></label>
        <p className="text-xs leading-5 text-slate-500 sm:col-span-2">보관 주기가 비어 있으면 제한을 두지 않습니다. 설정을 저장해도 녹음을 자동 삭제하지 않습니다. 유료 작업 시간·예산 제한은 새 외부 API 요청에 적용하며, 현재 진행 중인 로컬 녹음은 중단하지 않습니다.</p>
        <button className="button-primary justify-self-start" disabled={!settings || busy}>설정 저장</button>
      </form>
    </section>
  </main>
}
