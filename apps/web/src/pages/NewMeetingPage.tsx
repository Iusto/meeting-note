import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { ArrowLeft, ArrowRight, CheckCircle2, LoaderCircle, MicOff, ShieldCheck } from '../icons'
import { ApiError, api } from '../api'
import { MockNotice } from '../components'
import { AppLink, navigate } from '../router'
import { knowledgeApi } from '../knowledgeApi'
import type { Topic } from '../knowledgeApi'

export function NewMeetingPage({ onUnauthorized }: { onUnauthorized: () => void }) {
  const [title, setTitle] = useState('')
  const [acknowledged, setAcknowledged] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const [inputSource, setInputSource] = useState<'MOCK' | 'LOCAL_RECORDING'>('MOCK')
  const [topics, setTopics] = useState<Topic[]>([])
  const [topicId, setTopicId] = useState('')
  useEffect(() => {
    knowledgeApi.topics().then(setTopics).catch((caught) => {
      if (caught instanceof ApiError && caught.status === 401) onUnauthorized()
      else setError('주제를 불러오지 못했습니다. 주제 없이 회의를 만들 수 있습니다.')
    })
  }, [onUnauthorized])

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!title.trim() || !acknowledged) return
    setSubmitting(true)
    setError('')
    try {
      const meeting = await api.createMeeting(title.trim(), inputSource, topicId)
      await api.startMeeting(meeting.id)
      navigate(`/meetings/${meeting.id}/live`)
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) onUnauthorized()
      else setError(caught instanceof Error ? caught.message : '회의를 만들지 못했습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="mx-auto max-w-5xl px-5 py-10 md:px-10 md:py-14">
      <AppLink href="/meetings" className="inline-flex items-center gap-2 text-sm font-bold text-moss hover:underline"><ArrowLeft className="h-4 w-4" />회의 목록</AppLink>
      <div className="mt-8 grid gap-8 lg:grid-cols-[1fr_20rem]">
        <section className="rounded-[2rem] border border-white bg-white p-7 shadow-soft md:p-10">
          <p className="eyebrow">New meeting</p><h1 className="mt-2 text-3xl font-black tracking-[-0.04em] md:text-4xl">새 회의 준비</h1><p className="mt-3 text-sm leading-6 text-slate-500">가상 전사를 확인하거나 마이크 녹음을 개인 서버에 보관하세요. 외부 AI 호출은 없습니다.</p>
          <form className="mt-9" onSubmit={submit}>
            <label htmlFor="title" className="text-sm font-black">회의 제목</label>
            <input id="title" autoFocus className="field mt-2" value={title} onChange={(event) => setTitle(event.target.value)} maxLength={120} placeholder="예: MES 주간 개발 회의" />
            <label htmlFor="meeting-topic" className="mt-5 block text-sm font-black">회의 주제</label>
            <select id="meeting-topic" className="field mt-2" value={topicId} onChange={(event) => setTopicId(event.target.value)}><option value="">주제 없음</option>{topics.map((topic) => <option key={topic.id} value={topic.id}>{topic.name}</option>)}</select>
            <div className="mt-7 rounded-2xl bg-cream p-5">
              <label htmlFor="input-source" className="text-sm font-black">회의 모드</label>
              <select id="input-source" className="field mt-2" value={inputSource} onChange={(event) => { setInputSource(event.target.value as typeof inputSource); setAcknowledged(false) }}>
                <option value="MOCK">가상 전사 데모 · 마이크 사용 안 함</option>
                <option value="LOCAL_RECORDING">마이크 녹음 · 음성 인식 미연결</option>
              </select>
              <p className="mt-2 flex items-center gap-2 text-xs text-slate-500"><MicOff className="h-4 w-4" />{inputSource === 'MOCK' ? '합성 텍스트로 화면과 저장을 확인합니다.' : '다음 화면에서 장치와 녹음 동의를 확인합니다.'}</p>
            </div>
            <label className="mt-6 flex cursor-pointer items-start gap-3 rounded-2xl border border-moss/15 p-4 has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-moss">
              <input type="checkbox" className="mt-1 h-4 w-4 accent-moss" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)} />
              <span className="text-sm leading-6 text-slate-600"><strong className="text-ink">{inputSource === 'MOCK' ? '가상 스트림임을 확인했습니다.' : '녹음 전용 모드임을 확인했습니다.'}</strong><br />{inputSource === 'MOCK' ? '녹음·외부 전송·유료 API 호출이 발생하지 않습니다.' : '음성은 개인 서버에만 보관하며 자막은 생성되지 않습니다.'}</span>
            </label>
            {error && <p role="alert" className="mt-4 text-sm font-semibold text-red-700">{error}</p>}
            <button className="button-primary mt-7" disabled={!title.trim() || !acknowledged || submitting}>{submitting ? <LoaderCircle className="h-5 w-5 animate-spin" /> : <ArrowRight className="h-5 w-5" />}{inputSource === 'MOCK' ? '가상 회의 시작' : '녹음 회의 준비'}</button>
          </form>
        </section>
        <aside className="space-y-4">{inputSource === 'MOCK' && <MockNotice />}<div className="rounded-2xl bg-moss p-5 text-white"><ShieldCheck className="h-6 w-6 text-mint" /><h2 className="mt-4 font-black">안전 원칙</h2><ul className="mt-3 space-y-2 text-sm leading-6 text-white/75"><li className="flex gap-2"><CheckCircle2 className="mt-1 h-4 w-4 shrink-0" />원문을 교정문으로 덮어쓰지 않음</li><li className="flex gap-2"><CheckCircle2 className="mt-1 h-4 w-4 shrink-0" />화자 상태와 전사 상태를 분리</li><li className="flex gap-2"><CheckCircle2 className="mt-1 h-4 w-4 shrink-0" />비밀 키를 브라우저에 두지 않음</li></ul></div></aside>
      </div>
    </main>
  )
}
