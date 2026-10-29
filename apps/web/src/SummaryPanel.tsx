import { useEffect, useState } from 'react'
import { ApiError } from './api'
import { knowledgeApi } from './knowledgeApi'
import type { CorrectionSuggestion, MeetingSummary } from './knowledgeApi'
import type { AiConfiguration } from './types'

function Sources({ ids }: { ids: string[] }) {
  return <div className="mt-2 flex flex-wrap gap-2">{ids.map((id, index) => <a key={id} className="text-xs font-bold text-moss underline" href={`#segment-${id}`}>근거 발언 {index + 1}</a>)}</div>
}

export function SummaryPanel({ meetingId, stopped, transcriptKey, hasTopic, aiConfiguration, onChanged }: { meetingId: string; stopped: boolean; transcriptKey: string; hasTopic: boolean; aiConfiguration?: AiConfiguration; onChanged: () => Promise<void> }) {
  const [summary, setSummary] = useState<MeetingSummary>()
  const [suggestions, setSuggestions] = useState<CorrectionSuggestion[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const paidLlm = Boolean(aiConfiguration?.enabled && aiConfiguration.llmAvailable)
  useEffect(() => {
    let active = true
    knowledgeApi.summary(meetingId).then((value) => { if (active) setSummary(value) }).catch((caught) => {
      if (!active) return
      if (caught instanceof ApiError && caught.status === 404) setSummary(undefined)
      else setError(caught instanceof Error ? caught.message : '요약을 불러오지 못했습니다.')
    })
    return () => { active = false }
  }, [meetingId, transcriptKey])

  async function act(action: () => Promise<void>) {
    setBusy(true); setError('')
    try { await action() }
    catch (caught) { setError(caught instanceof Error ? caught.message : '요청을 처리하지 못했습니다. 최신 발언을 확인하세요.') }
    finally { setBusy(false) }
  }

  return <section className="mt-6 space-y-4 rounded-3xl border border-moss/10 bg-white p-6" aria-label="요약과 검토">
    <h2 className="text-xl font-black">요약과 검토</h2>
    <p className="text-sm leading-6 text-slate-500">{paidLlm ? '요약과 교정 버튼을 누르면 유료 AI 요청이 발생할 수 있습니다. 자동으로 제출하지 않으며, 결과는 항상 원문 근거와 함께 검토하세요.' : '현재 요약은 확정 발언을 인용하는 가상 결과입니다. 실제 AI의 요약·교정 품질을 나타내지 않습니다.'} 원문 근거 없이 결정·담당자·기한을 만들지 않습니다.</p>
    {error && <p role="alert" className="rounded-xl bg-red-50 p-3 text-sm text-red-700">{error}</p>}
    <div className="flex flex-wrap gap-3">
      <button className="button-secondary" disabled={busy || !stopped || !transcriptKey} onClick={() => void act(async () => { setSummary(await knowledgeApi.generateSummary(meetingId)) })}>{summary ? `${paidLlm ? 'AI' : '가상'} 요약 다시 생성` : `${paidLlm ? 'AI' : '가상'} 요약 생성`}</button>
      <button className="button-secondary" disabled={busy || !stopped || !hasTopic || !transcriptKey} onClick={() => void act(async () => { setSuggestions(await knowledgeApi.corrections(meetingId)) })}>{paidLlm ? 'AI 사전 교정 후보 확인' : '사전 교정 후보 확인'}</button>
    </div>
    {!hasTopic && <p className="text-xs text-slate-500">주제가 있는 회의에서 승인된 사전의 교정 후보를 확인할 수 있습니다.</p>}
    {summary && <section className="rounded-2xl bg-cream p-5" aria-label="저장된 요약">
      <p className="text-xs font-black text-moss">{summary.provider} · 요약 버전 {summary.version}</p>
      {summary.stale && <p role="status" className="mt-3 font-bold text-amber-800">발언이 수정되어 요약 갱신이 필요합니다.</p>}
      <p className="mt-3 whitespace-pre-wrap text-sm leading-7">{summary.content.summary}</p>
      <Sources ids={summary.content.sourceSegmentIds} />
      <h3 className="mt-5 text-sm font-black">결정사항</h3>
      {summary.content.decisions.length ? summary.content.decisions.map((item, index) => <div key={index} className="mt-2 text-sm">{item.text}<Sources ids={item.sourceSegmentIds} /></div>) : <p className="mt-1 text-sm text-slate-500">확인된 결정사항 없음</p>}
      <h3 className="mt-5 text-sm font-black">액션아이템</h3>
      {summary.content.actionItems.length ? summary.content.actionItems.map((item, index) => <div key={index} className="mt-2 text-sm">{item.text}<p className="text-xs text-slate-500">담당자: {item.assignee ?? '미상'} · 기한: {item.dueDate ?? '미상'}</p><Sources ids={item.sourceSegmentIds} /></div>) : <p className="mt-1 text-sm text-slate-500">확인된 후속 업무 없음</p>}
      <h3 className="mt-5 text-sm font-black">확인할 사항</h3>
      {summary.content.unresolved.length ? summary.content.unresolved.map((item, index) => <div key={index} className="mt-2 text-sm">{item.text}<Sources ids={item.sourceSegmentIds} /></div>) : <p className="mt-1 text-sm text-slate-500">추가 확인 사항 없음</p>}
    </section>}
    {suggestions.map((suggestion) => <article key={suggestion.id} className="rounded-xl border border-moss/15 p-4 text-sm">
      <p className="font-bold">{suggestion.originalSpan} → {suggestion.replacement}</p><p className="mt-1 text-xs text-slate-500">{suggestion.reason} · {suggestion.state}</p>
      <Sources ids={[suggestion.segmentId]} />
      {suggestion.state === 'PENDING' && <div className="mt-3 flex gap-3">
        <button className="text-xs font-bold text-moss underline" disabled={busy} onClick={() => void act(async () => { const result = await knowledgeApi.reviewCorrection(meetingId, suggestion.id, 'APPLIED'); setSuggestions((list) => list.map((item) => item.id === result.id ? result : item)); await onChanged() })}>검토 후 적용</button>
        <button className="text-xs font-bold text-slate-500 underline" disabled={busy} onClick={() => void act(async () => { const result = await knowledgeApi.reviewCorrection(meetingId, suggestion.id, 'REJECTED'); setSuggestions((list) => list.map((item) => item.id === result.id ? result : item)) })}>교정 반려</button>
      </div>}
    </article>)}
    {suggestions.length === 0 && <p className="text-xs text-slate-500">안전하게 제안할 수 있는 교정이 없으면 원문을 유지합니다. 숫자와 식별번호, 모호한 로또 표현은 자동으로 바꾸지 않습니다.</p>}
  </section>
}
