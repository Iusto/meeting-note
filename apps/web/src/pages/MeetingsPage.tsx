import { useEffect, useState } from 'react'
import { ArrowRight, CalendarDays, CirclePlus, Clock3, LoaderCircle, Radio } from '../icons'
import { ApiError, api } from '../api'
import { MockNotice, TrustStrip } from '../components'
import { AppLink } from '../router'
import type { Meeting } from '../types'

const statusLabel: Record<string, string> = { CREATED: '준비됨', STARTED: '진행 중', STOPPED: '종료됨', COMPLETED: '완료' }

function dateLabel(value?: string) {
  if (!value) return '아직 시작 전'
  return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

export function MeetingsPage({ onUnauthorized }: { onUnauthorized: () => void }) {
  const [meetings, setMeetings] = useState<Meeting[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [draftQuery, setDraftQuery] = useState('')
  const [query, setQuery] = useState('')

  useEffect(() => {
    let active = true
    setLoading(true); setError('')
    api.listMeetings(query).then((rows) => { if (active) setMeetings(rows) }).catch((caught) => {
      if (!active) return
      if (caught instanceof ApiError && caught.status === 401) onUnauthorized()
      else setError(caught instanceof Error ? caught.message : '회의를 불러오지 못했습니다.')
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [onUnauthorized, query])

  return (
    <main className="mx-auto max-w-7xl px-5 py-10 md:px-10 md:py-16">
      <section className="grid items-end gap-8 lg:grid-cols-[1fr_25rem]">
        <div>
          <p className="eyebrow">나의 회의 작업실</p>
          <h1 className="mt-3 max-w-2xl text-4xl font-black leading-[1.1] tracking-[-0.055em] text-ink md:text-6xl">흘러가는 대화를<br /><span className="text-moss">놓치지 않도록.</span></h1>
          <p className="mt-5 max-w-xl text-base leading-7 text-slate-500">임시 자막부터 확정 원문, 늦게 도착한 화자 라벨과 교정까지 시간순으로 모아봅니다.</p>
          <AppLink className="button-primary mt-7 inline-flex" href="/meetings/new"><CirclePlus className="h-5 w-5" />새 회의 만들기</AppLink>
        </div>
        <div className="space-y-4"><MockNotice /><TrustStrip /></div>
      </section>

      <section className="mt-16">
        <div className="mb-5 flex items-center justify-between">
          <div><p className="eyebrow">Recent meetings</p><h2 className="mt-1 text-2xl font-black tracking-tight">최근 회의</h2></div>
          <span className="text-sm font-bold text-moss/60">{meetings.length}개</span>
        </div>
        <form className="mb-5 flex flex-wrap items-end gap-3" onSubmit={(event) => { event.preventDefault(); setQuery(draftQuery.trim()) }} role="search">
          <label className="min-w-56 flex-1 text-sm font-bold" htmlFor="meeting-search">회의 검색<input id="meeting-search" className="field mt-2" maxLength={200} placeholder="제목·원문·교정문 검색" value={draftQuery} onChange={(event) => setDraftQuery(event.target.value)} /></label>
          <button className="button-primary" disabled={loading}>검색</button>
          <button type="button" className="button-secondary" disabled={loading} onClick={() => { setDraftQuery(''); setQuery('') }}>전체 보기</button>
        </form>
        {loading && <div className="grid place-items-center rounded-[2rem] bg-white py-20"><LoaderCircle className="h-7 w-7 animate-spin text-moss" /></div>}
        {error && <div role="alert" className="rounded-2xl border border-red-200 bg-red-50 p-5 text-sm font-semibold text-red-800">{error}</div>}
        {!loading && !error && meetings.length === 0 && (
          <div className="rounded-[2rem] border border-dashed border-moss/20 bg-white/50 px-6 py-16 text-center">
            <Radio className="mx-auto h-10 w-10 text-moss/30" /><h3 className="mt-4 text-lg font-black">{query ? '검색 결과가 없습니다' : '첫 회의를 만들어 보세요'}</h3><p className="mt-2 text-sm text-slate-500">{query ? '다른 검색어를 입력하거나 전체 보기를 선택하세요.' : '가상 스트림이 임시 발언부터 교정까지 안전하게 보여줍니다.'}</p>
          </div>
        )}
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {!loading && !error && meetings.map((meeting) => (
            <AppLink key={meeting.id} href={`/meetings/${meeting.id}/live`} className="group rounded-[1.7rem] border border-white bg-white p-6 shadow-sm transition hover:-translate-y-1 hover:shadow-soft focus:outline-none focus-visible:ring-2 focus-visible:ring-moss">
              <div className="flex items-center justify-between"><span className={`meeting-status ${meeting.status === 'STARTED' ? 'meeting-status-live' : ''}`}>{meeting.status === 'STARTED' && <span className="h-2 w-2 animate-pulse rounded-full bg-coral" />}{statusLabel[meeting.status] ?? meeting.status}</span><ArrowRight className="h-5 w-5 text-moss/30 transition group-hover:translate-x-1 group-hover:text-moss" /></div>
              <h3 className="mt-5 line-clamp-2 text-xl font-black leading-7 tracking-tight">{meeting.title}</h3>
              <div className="mt-6 flex items-center gap-2 text-xs font-semibold text-slate-400"><CalendarDays className="h-4 w-4" />{dateLabel(meeting.startedAt ?? meeting.createdAt)}</div>
            </AppLink>
          ))}
        </div>
      </section>
      <footer className="mt-14 flex items-center gap-2 border-t border-moss/10 pt-6 text-xs text-slate-400"><Clock3 className="h-4 w-4" />실측 전 성능 수치는 목표이며 보장값이 아닙니다.</footer>
    </main>
  )
}
