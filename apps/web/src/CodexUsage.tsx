import { useCallback, useEffect, useState } from 'react'
import { ApiError, request } from './api'

interface Quota {
  checkedAt: string
  ordinaryUsageAllowed: boolean | null
  limits: { id: string; name: string; planType: string | null; spendControlReached: boolean | null; reachedType: string | null
    windows: { remainingPercent: number; windowMinutes: number | null; resetsAt: number | null }[] }[]
}

function windowLabel(minutes: number | null) {
  if (minutes === null) return '사용 한도'
  if (minutes >= 1440) return `${minutes / 1440}일 한도`
  return minutes >= 60 ? `${minutes / 60}시간 한도` : `${minutes}분 한도`
}

export function CodexUsage({ onUnauthorized }: { onUnauthorized: () => void }) {
  const [quota, setQuota] = useState<Quota | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const load = useCallback(async () => {
    setBusy(true); setError('')
    try { setQuota(await request<Quota>('/ai/configuration/usage')) }
    catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) onUnauthorized()
      else setError(caught instanceof Error ? caught.message : 'Codex 사용량을 조회하지 못했습니다.')
    } finally { setBusy(false) }
  }, [onUnauthorized])
  useEffect(() => { void load() }, [load])
  return <section className="mt-6 rounded-2xl border border-moss/10 bg-white p-6" aria-label="Codex 구독 사용량">
    <div className="flex flex-wrap items-center justify-between gap-3"><h2 className="text-xl font-black">Codex 남은 사용량</h2><button className="button-secondary" disabled={busy} onClick={() => void load()}>{busy ? '조회 중…' : '새로고침'}</button></div>
    <p className="mt-3 text-sm leading-6 text-slate-500">요약·교정에 사용하는 계정 전체 한도입니다. 다른 Codex 작업과 공유하며, 서버 조회 결과를 최대 30초간 재사용합니다.</p>
    {error && <p role="alert" className="mt-4 text-sm text-red-700">{error}{quota && ' 아래 값은 마지막 조회 결과입니다.'}</p>}
    {quota && <>
      {quota.ordinaryUsageAllowed === false && <p className="mt-4 text-sm text-amber-800">현재 구독 포함 사용이 제한되어 있습니다. 잔여율과 별개로 서버의 사용 제한이 적용됩니다.</p>}
      <div className="mt-5 space-y-5">{quota.limits.map(limit => <div key={limit.id}>
        <h3 className="font-bold">{limit.name}{limit.planType && ` · ${limit.planType}`}</h3>
        {(limit.spendControlReached || limit.reachedType) && <p className="mt-2 text-sm text-amber-800">이 항목에 서버 사용 제한이 적용되어 있습니다.</p>}
        <div className="mt-3 grid gap-4 sm:grid-cols-2">{limit.windows.map((window, index) => <div key={index} className="rounded-xl bg-mint/40 p-4">
          <p className="text-sm text-slate-500">{windowLabel(window.windowMinutes)}</p>
          <p className="mt-1 text-3xl font-black">{window.remainingPercent.toFixed(0)}% <span className="text-sm font-normal">남음</span></p>
          <progress className="mt-3 h-2 w-full accent-moss" max={100} value={window.remainingPercent} aria-label={`${windowLabel(window.windowMinutes)} 잔여율`} />
          <p className="mt-2 text-xs text-slate-500">{window.resetsAt ? `갱신: ${new Date(window.resetsAt * 1000).toLocaleString('ko-KR')}` : '갱신 시간 제공 안 됨'}</p>
        </div>)}</div>
        {limit.windows.length === 0 && <p className="mt-3 text-sm text-slate-500">이 항목의 잔여율이 제공되지 않았습니다.</p>}
      </div>)}</div>
      {quota.limits.length === 0 && <p className="mt-4 text-sm text-slate-500">계정에서 사용 한도 정보가 제공되지 않았습니다.</p>}
      <p className="mt-4 text-xs text-slate-500">마지막 확인: {new Date(quota.checkedAt).toLocaleString('ko-KR')}</p>
    </>}
  </section>
}
