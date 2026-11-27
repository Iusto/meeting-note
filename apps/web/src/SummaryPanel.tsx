import { createContext, useContext, useEffect, useState } from 'react'
import { ApiError } from './api'
import { knowledgeApi } from './knowledgeApi'
import type { CorrectionSuggestion, MeetingSummary, SummaryProgress } from './knowledgeApi'
import type { AiConfiguration, TranscriptSegment } from './types'

const EvidenceContext = createContext<Record<string, { time: string; speaker: string; text: string }>>({})

function Sources({ ids }: { ids: string[] }) {
  const evidence = useContext(EvidenceContext)
  return <details className="mt-2 text-xs text-slate-500"><summary className="cursor-pointer font-bold text-moss">근거 발언 {ids.length}개 보기</summary><ul className="mt-2 space-y-2">{ids.map(id => <li key={id}><a className="font-bold text-moss underline" href={`#segment-${id}`}>{evidence[id] ? `${evidence[id].time} · ${evidence[id].speaker}` : '원문 발언 보기'}</a>{evidence[id] && <p className="mt-1 leading-5">{evidence[id].text}</p>}</li>)}</ul></details>
}

export function SummaryPanel({ meetingId, stopped, transcriptKey, hasTopic, aiConfiguration, importProcessing, onChanged, segments = [], speakerName }: { meetingId: string; stopped: boolean; transcriptKey: string; hasTopic: boolean; aiConfiguration?: AiConfiguration; importProcessing?: boolean; onChanged: () => Promise<void>; segments?: TranscriptSegment[]; speakerName?: (label?: string | null) => string }) {
  const [summary, setSummary] = useState<MeetingSummary>()
  const [suggestions, setSuggestions] = useState<CorrectionSuggestion[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [startedAt, setStartedAt] = useState<number>()
  const [finished, setFinished] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const [progress, setProgress] = useState<SummaryProgress>()
  const activeProgress = progress && progress.stage !== 'IDLE' && progress.stage !== 'COMPLETED'
  const generating = startedAt !== undefined || activeProgress || importProcessing
  const stageLabel = progress?.stage === 'CHUNKS'
    ? (progress.totalChunks === 1 ? 'AI가 전사문을 읽고 요약을 작성하는 중' : '전사 구간 처리 ' + progress.completedChunks + '/' + progress.totalChunks)
    : progress?.stage === 'SYNTHESIS' ? '구간별 요약을 회의록으로 합치는 중'
      : progress?.stage === 'SAVING' ? '요약과 회의록 저장 중' : '요약 작업 준비 중'
  const codex = aiConfiguration?.llmProvider === 'CODEX'
  const realLlm = Boolean(aiConfiguration?.llmAvailable)
  useEffect(() => {
    let active = true
    knowledgeApi.summary(meetingId).then((value) => { if (active) setSummary(value) }).catch((caught) => {
      if (!active) return
      if (caught instanceof ApiError && caught.status === 404) setSummary(undefined)
      else setError(caught instanceof Error ? caught.message : '요약을 불러오지 못했습니다.')
    })
    return () => { active = false }
  }, [meetingId, transcriptKey])

  useEffect(() => {
    if (!stopped || (!transcriptKey && !importProcessing)) return
    const controller = new AbortController()
    let hadActiveJob = false
    let timer: number
    const poll = async () => {
      try {
        const value = await knowledgeApi.summaryProgress(meetingId, controller.signal)
        if (controller.signal.aborted) return
        setProgress(value)
        const activeJob = value.stage !== 'IDLE' && value.stage !== 'COMPLETED'
        const jobEnded = hadActiveJob && !activeJob
        hadActiveJob = activeJob
        if (jobEnded) {
          const saved = await knowledgeApi.summary(meetingId)
          if (!controller.signal.aborted) setSummary(saved)
        }
      } catch {
        // The generate request and import status report errors for the job itself.
      } finally {
        if (!controller.signal.aborted) timer = window.setTimeout(poll, hadActiveJob ? 1000 : 2000)
      }
    }
    void poll()
    return () => { controller.abort(); window.clearTimeout(timer) }
  }, [meetingId, stopped, transcriptKey, importProcessing])

  useEffect(() => {
    if (startedAt === undefined) return
    const timer = window.setInterval(() => setElapsed(Math.floor((Date.now() - startedAt) / 1000)), 1000)
    return () => window.clearInterval(timer)
  }, [startedAt])

  async function generate() {
    setProgress(undefined)
    setFinished(false)
    setElapsed(0)
    setStartedAt(Date.now())
    try {
      await act(async () => {
        setSummary(await knowledgeApi.generateSummary(meetingId))
        setFinished(true)
      })
    } finally { setStartedAt(undefined) }
  }

  async function act(action: () => Promise<void>) {
    setBusy(true); setError('')
    try { await action() }
    catch (caught) { setError(caught instanceof Error ? caught.message : '요청을 처리하지 못했습니다. 최신 발언을 확인하세요.') }
    finally { setBusy(false) }
  }

  const evidence = Object.fromEntries(segments.map(segment => [segment.segmentId, {
    time: `${Math.floor(segment.startMs / 60000)}:${String(Math.floor(segment.startMs / 1000) % 60).padStart(2, '0')}`,
    speaker: speakerName?.(segment.speakerLabel) ?? segment.speakerLabel ?? '화자 미정', text: segment.displayText,
  }]))
  return <EvidenceContext.Provider value={evidence}><section className="space-y-4 rounded-3xl border border-moss/10 bg-white p-6" aria-label="요약과 검토">
    <h2 className="text-xl font-black">요약과 검토</h2>
    <p className="text-sm leading-6 text-slate-500">{codex ? '요약·교정은 서버 PC의 Codex 요금제로 처리합니다. 별도 OpenAI API 요금은 없으며 Codex 사용 한도를 소비합니다. PC와 Codex 로그인이 유지되어야 합니다.' : realLlm ? '요약과 교정 버튼을 누르면 유료 AI 요청이 발생할 수 있습니다. 자동으로 제출하지 않으며, 결과는 항상 원문 근거와 함께 검토하세요.' : '현재 요약은 확정 발언을 인용하는 가상 결과입니다. 실제 AI의 요약·교정 품질을 나타내지 않습니다.'} 원문 근거 없이 결정·담당자·기한을 만들지 않습니다.</p>
    {(startedAt !== undefined || activeProgress) && <div className="space-y-2 rounded-xl bg-cream p-3">
      <div className="flex items-baseline justify-between gap-3 text-sm font-bold text-moss">
        <span role="status">{stageLabel}</span><span>{activeProgress ? progress.percent : 0}%</span>
      </div>
      <progress className="h-2 w-full accent-moss" value={activeProgress ? progress.percent : 0} max={100} aria-label="AI 요약 진행률" />
      {startedAt !== undefined && <p className="text-xs text-slate-500">경과 {Math.floor(elapsed / 60)}분 {elapsed % 60}초</p>}
    </div>}
    {finished && !generating && <p role="status" className="text-sm font-bold text-moss">요약 저장 완료 · 100%</p>}
    {busy && startedAt === undefined && <p role="status" className="text-sm font-bold text-moss">{codex ? 'Codex가 처리 중입니다. 긴 회의는 몇 분 걸릴 수 있습니다.' : 'AI가 처리 중입니다.'}</p>}
    {error && <p role="alert" className="rounded-xl bg-red-50 p-3 text-sm text-red-700">{error}</p>}
    <div className="flex flex-wrap gap-3">
      <button className="button-secondary" disabled={busy || generating || !stopped || !transcriptKey} onClick={() => void generate()}>{summary ? `${realLlm ? 'AI' : '가상'} 요약 다시 생성` : `${realLlm ? 'AI' : '가상'} 요약 생성`}</button>
      <button className="button-secondary" disabled={busy || generating || !stopped || !hasTopic || !transcriptKey} onClick={() => void act(async () => { setSuggestions(await knowledgeApi.corrections(meetingId)) })}>{realLlm ? 'AI 사전 교정 후보 확인' : '사전 교정 후보 확인'}</button>
    </div>
    {!hasTopic && <p className="text-xs text-slate-500">주제가 있는 회의에서 승인된 사전의 교정 후보를 확인할 수 있습니다.</p>}
    {summary && <section className="rounded-2xl bg-cream p-5" aria-label="저장된 요약">
      <p className="text-xs font-black text-moss">{summary.provider} · 요약 버전 {summary.version}</p>
      {summary.stale && <p role="status" className="mt-3 font-bold text-amber-800">발언이 수정되어 요약 갱신이 필요합니다.</p>}
      <h3 className="mt-5 text-sm font-black">핵심 요약</h3>
      <p className="mt-3 whitespace-pre-wrap text-sm leading-7">{summary.content.summary}</p>
      <Sources ids={summary.content.sourceSegmentIds} />
      <h3 className="mt-5 text-sm font-black">주제별 논의</h3>
      {summary.content.discussions?.length ? summary.content.discussions.map((item, index) => <div key={index} className="mt-3 whitespace-pre-wrap text-sm leading-6">{item.text}<Sources ids={item.sourceSegmentIds} /></div>) : <p className="mt-1 text-sm text-slate-500">{summary.content.discussions === undefined ? '이전 형식의 요약입니다. 다시 생성하면 주제별 논의가 포함됩니다.' : '확인된 주제별 논의 없음'}</p>}
      <h3 className="mt-5 text-sm font-black">확정 결정</h3>
      {summary.content.decisions.length ? summary.content.decisions.map((item, index) => <div key={index} className="mt-2 text-sm">{item.text}<Sources ids={item.sourceSegmentIds} /></div>) : <p className="mt-1 text-sm text-slate-500">확인된 결정사항 없음</p>}
      <h3 className="mt-5 text-sm font-black">할 일</h3>
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
  </section></EvidenceContext.Provider>
}
