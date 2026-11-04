import { useState } from 'react'
import type { TranscriptSegment } from './types'
import { findTranscriptMatches } from './transcriptSearchMatches'

export function TranscriptSearch({ segments, speakerName, onNavigate }: { segments: TranscriptSegment[]; speakerName: (label?: string | null) => string; onNavigate: () => void }) {
  const [query, setQuery] = useState('')
  const [selectedId, setSelectedId] = useState('')
  const matches = findTranscriptMatches(segments, query, speakerName)
  const selected = matches.findIndex(segment => segment.segmentId === selectedId)
  function jump(direction: number) {
    if (!matches.length) return
    const index = selected < 0 ? (direction < 0 ? matches.length - 1 : 0) : (selected + direction + matches.length) % matches.length
    const segment = matches[index]
    setSelectedId(segment.segmentId); onNavigate()
    const element = document.getElementById(`segment-${segment.segmentId}`)
    element?.scrollIntoView({ block: 'center', behavior: 'smooth' })
    element?.focus({ preventScroll: true })
  }
  return <form role="search" aria-label="이 회의 발언 검색" className="mt-3 rounded-2xl bg-white p-4" onSubmit={event => { event.preventDefault(); jump(1) }}>
    <label className="text-sm font-bold">발언·화자 검색<input className="field mt-2" type="search" maxLength={200} placeholder="이 회의의 원문·교정문·화자 이름" value={query} onChange={event => { setQuery(event.target.value); setSelectedId('') }} /></label>
    {query.trim() && <div className="mt-2 flex flex-wrap items-center gap-3 text-xs"><span role="status">{matches.length ? `${selected < 0 ? 0 : selected + 1} / ${matches.length}개 일치` : '검색 결과가 없습니다'}</span><button type="button" className="font-bold text-moss underline" disabled={!matches.length} onClick={() => jump(-1)}>이전 결과</button><button className="font-bold text-moss underline" disabled={!matches.length}>다음 결과</button><button type="button" onClick={() => { setQuery(''); setSelectedId('') }}>검색 지우기</button></div>}
  </form>
}

