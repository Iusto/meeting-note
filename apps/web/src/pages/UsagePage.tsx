import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, api } from '../api'
import { usageApi } from '../usageApi'
import type { Settings } from '../usageApi'
import type { AiConfiguration } from '../types'
import { CodexUsage } from '../CodexUsage'

export function UsagePage({ onUnauthorized }: { onUnauthorized: () => void }) {
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

  async function save(event: FormEvent) {
    event.preventDefault()
    if (!settings) return
    setBusy(true); setError(''); setNotice('')
    try {
      const updated = await usageApi.saveSettings({ monthlyBudgetUsd: Number(budget), perMeetingBudgetUsd: Number(meetingBudget), warnAtPercent: Number(warn), perMeetingMaxMinutes: Number(minutes), recordingRetentionDays: retention === '' ? null : Number(retention), revision: settings.revision })
      setSettings(updated)
      setAiConfiguration(await api.getAiConfiguration())
      setNotice(`설정을 저장했습니다.${aiConfiguration?.enabled ? ' 새 유료 요청부터 변경한 상한을 적용합니다.' : ' 유료 API는 계속 차단됩니다.'}`)
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) onUnauthorized()
      else setError(caught instanceof ApiError && caught.status === 409 ? '다른 탭에서 설정을 변경했습니다. 초안을 보관하고 새로고침하세요.' : caught instanceof Error ? caught.message : '설정을 저장하지 못했습니다.')
    } finally { setBusy(false) }
  }

  return <main className="mx-auto max-w-5xl px-5 py-10 md:px-10">
    <p className="eyebrow">개인 작업 공간</p><h1 className="mt-3 text-3xl font-black">사용량</h1>
    {aiConfiguration?.llmProvider === 'CODEX' && <CodexUsage onUnauthorized={onUnauthorized} />}
    <p className="mt-4 rounded-2xl bg-mint p-4 text-sm leading-6 text-moss">{aiConfiguration?.llmProvider === 'CODEX' ? '요약·교정·용어 후보는 Codex 구독 한도를 사용합니다. LIVE 음성 전사는 별도 Deepgram API 비용이 발생하며 하단의 전사 예산 설정이 적용됩니다.' : aiConfiguration?.enabled ? '외부 API 요청에는 하단의 예산과 서버 상한이 적용됩니다.' : '외부 API 호출이 차단되어 있습니다. 가상 전사·요약과 로컬 녹음의 외부 API 비용은 0 USD입니다.'}</p>
    {error && <p role="alert" className="mt-4 text-sm text-red-700">{error}</p>}
    {notice && <p role="status" className="mt-4 text-sm text-moss">{notice}</p>}
    <details className="mt-6 rounded-2xl border border-moss/10 bg-white p-6" aria-label="예산 및 보관 설정">
      <summary className="cursor-pointer text-lg font-bold">전사 API 예산·녹음 보관 설정</summary>
      <form className="mt-5 grid gap-4 sm:grid-cols-2" onSubmit={(event) => void save(event)}>
        <label className="text-sm font-bold">월 예산 (USD)<input type="number" className="field mt-2" required min="0" max="10000" step="0.01" disabled={!settings || busy} value={budget} onChange={(event) => setBudget(event.target.value)} /></label>
        <label className="text-sm font-bold">회의당 최대 예산 (USD)<input type="number" className="field mt-2" required min="0.01" max="10000" step="0.01" disabled={!settings || busy} value={meetingBudget} onChange={(event) => setMeetingBudget(event.target.value)} /></label>
        <label className="text-sm font-bold">예산 경고 기준 (%)<input type="number" className="field mt-2" required min="1" max="100" step="1" disabled={!settings || busy} value={warn} onChange={(event) => setWarn(event.target.value)} /></label>
        <label className="text-sm font-bold">유료 작업 회당 최대 시간 (분)<input type="number" className="field mt-2" required min="1" max="1440" step="1" disabled={!settings || busy} value={minutes} onChange={(event) => setMinutes(event.target.value)} /></label>
        <label className="text-sm font-bold">녹음 보관 검토 주기 (일)<input type="number" className="field mt-2" min="1" max="3650" step="1" placeholder="제한 없음" disabled={!settings || busy} value={retention} onChange={(event) => setRetention(event.target.value)} /></label>
        <p className="text-xs leading-5 text-slate-500 sm:col-span-2">보관 주기가 비어 있으면 제한을 두지 않습니다. 설정을 저장해도 녹음을 자동 삭제하지 않습니다. 유료 작업 시간·예산 제한은 새 외부 API 요청에 적용하며, 현재 진행 중인 로컬 녹음은 중단하지 않습니다.</p>
        <button className="button-primary justify-self-start" disabled={!settings || busy}>설정 저장</button>
      </form>
    </details>
  </main>
}
