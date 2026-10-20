import { Circle, Cloud, Radio, ShieldCheck, Sparkles } from './icons'
import type { SpeakerStatus, TranscriptSegment, TranscriptStatus } from './types'
import { useState } from 'react'

export function Brand() {
  return (
    <div className="flex items-center gap-3">
      <div className="grid h-10 w-10 place-items-center rounded-2xl bg-moss text-white shadow-lg shadow-moss/20">
        <span className="text-lg font-black">ㅁ</span>
      </div>
      <div>
        <p className="text-lg font-black tracking-[-0.04em] text-ink">모아</p>
        <p className="text-[10px] font-bold uppercase tracking-[0.22em] text-moss/60">Meeting Archive</p>
      </div>
    </div>
  )
}

const transcriptLabels: Record<TranscriptStatus, string> = {
  PARTIAL: '듣는 중',
  FINAL: '원문 확정',
  CORRECTION_PENDING: '교정 대기',
  CORRECTED: '표시 교정',
  REVIEW_REQUIRED: '검토 필요',
}

const speakerLabels: Record<SpeakerStatus, string> = {
  UNKNOWN: '화자 미정',
  TENTATIVE: '화자 추정',
  CONFIRMED: '화자 확인',
  OVERLAP: '겹친 발언',
}

function formatTime(ms: number) {
  const seconds = Math.floor(ms / 1000)
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}

export function TranscriptCard({ segment, partial = false, onSave }: { segment: TranscriptSegment; partial?: boolean; onSave?: (text: string, revision: number) => Promise<void> }) {
  const isCorrected = segment.transcriptStatus === 'CORRECTED' && segment.displayText !== segment.originalText
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')
  const [draftRevision, setDraftRevision] = useState(segment.revision)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  async function save() {
    setSaving(true); setError('')
    try { await onSave?.(draft.trim(), draftRevision); setEditing(false) }
    catch (caught) { setError(caught instanceof Error ? caught.message : '수정을 저장하지 못했습니다. 최신 발언을 확인하세요.') }
    finally { setSaving(false) }
  }
  return (
    <article id={`segment-${segment.segmentId}`} className={`group grid gap-4 rounded-[1.4rem] border p-5 transition md:grid-cols-[7.5rem_1fr] ${partial ? 'border-dashed border-moss/20 bg-white/45' : 'border-white/80 bg-white shadow-sm'}`}>
      <div>
        <p className="font-mono text-xs font-bold text-moss/55">{formatTime(segment.startMs)}</p>
        <div className="mt-2 inline-flex items-center gap-1.5 rounded-full bg-mint px-2.5 py-1 text-xs font-bold text-moss">
          <Circle className="h-2 w-2 fill-current" />
          {segment.speakerLabel ?? '화자 ?'}
        </div>
        <p className="mt-1.5 text-[11px] font-semibold text-slate-400">{speakerLabels[segment.speakerStatus]}</p>
      </div>
      <div className={partial ? 'opacity-55' : ''}>
        <div className="mb-2 flex flex-wrap items-center gap-2">
          <span className={`status-pill status-${segment.transcriptStatus.toLowerCase()}`}>{transcriptLabels[segment.transcriptStatus]}</span>
          {isCorrected && <span className="text-[11px] font-bold text-coral">원문 보존 · 표시만 교정</span>}
        </div>
        <p className={`text-[1.05rem] leading-7 text-ink ${partial ? 'italic' : 'font-medium'}`}>{segment.displayText}</p>
        {segment.userLocked && <p className="mt-2 text-xs font-bold text-moss">사용자 수정 보호됨</p>}
        {!partial && onSave && !editing && <button type="button" className="mt-3 text-xs font-bold text-moss underline" onClick={() => { setDraft(segment.displayText); setDraftRevision(segment.revision); setEditing(true); setError('') }}>발언 수정</button>}
        {editing && <div className="mt-3 space-y-2">
          <label htmlFor={`edit-${segment.segmentId}`} className="block text-sm font-bold">표시 문장 수정</label>
          <textarea id={`edit-${segment.segmentId}`} className="field min-h-24" maxLength={20_000} value={draft} onChange={(event) => setDraft(event.target.value)} disabled={saving} />
          <p className="text-xs text-slate-500">원문은 유지되며 이 문장은 자동 교정으로 덮어쓰지 않습니다.</p>
          {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
          <button className="button-primary" disabled={saving || !draft.trim()} onClick={() => void save()}>수정 저장</button>
          <button className="button-secondary ml-2" disabled={saving} onClick={() => setEditing(false)}>취소</button>
        </div>}
        {isCorrected && (
          <details className="mt-3 text-sm">
            <summary className="cursor-pointer select-none font-bold text-moss/70 outline-none focus-visible:ring-2 focus-visible:ring-moss">원문과 비교</summary>
            <p className="mt-2 rounded-xl bg-cream px-4 py-3 leading-6 text-slate-500 line-through decoration-coral/50">{segment.originalText}</p>
          </details>
        )}
      </div>
    </article>
  )
}

export function MockNotice() {
  return (
    <div className="flex gap-3 rounded-2xl border border-amber-200 bg-amber-50/90 p-4 text-sm leading-6 text-amber-950">
      <Sparkles className="mt-0.5 h-5 w-5 shrink-0 text-amber-600" />
      <p><strong>Phase 0 가상 스트림</strong><br />마이크를 켜거나 음성을 전송하지 않습니다. 화면의 발언은 서버가 만든 테스트 데이터입니다.</p>
    </div>
  )
}

export function TrustStrip() {
  return (
    <div className="grid gap-3 text-xs font-bold text-moss/70 sm:grid-cols-3">
      <span className="flex items-center gap-2"><ShieldCheck className="h-4 w-4" />서버 쿠키 인증</span>
      <span className="flex items-center gap-2"><Cloud className="h-4 w-4" />외부 API 호출 없음</span>
      <span className="flex items-center gap-2"><Radio className="h-4 w-4" />전사: 가상 이벤트</span>
    </div>
  )
}
