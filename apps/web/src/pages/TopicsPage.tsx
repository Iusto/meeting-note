import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from '../api'
import { knowledgeApi } from '../knowledgeApi'
import type { Term, TermInput, Topic } from '../knowledgeApi'
import { AppLink } from '../router'

const emptyTerm: TermInput = { canonical: '', meaning: '', aliases: [], status: 'PENDING' }
const statusLabels = { PENDING: '승인 대기', APPROVED: '승인됨', REJECTED: '반려됨' }

export function TopicsPage({ onUnauthorized }: { onUnauthorized: () => void }) {
  const [topics, setTopics] = useState<Topic[]>([])
  const [selected, setSelected] = useState('')
  const [newName, setNewName] = useState('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [terms, setTerms] = useState<Term[]>([])
  const [keyterms, setKeyterms] = useState<string[]>([])
  const [draft, setDraft] = useState<TermInput>(emptyTerm)
  const [aliasText, setAliasText] = useState('')
  const [editingId, setEditingId] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')

  function report(caught: unknown) {
    if (caught instanceof ApiError && caught.status === 401) onUnauthorized()
    else setError(caught instanceof Error ? caught.message : '사전을 불러오지 못했습니다.')
  }
  useEffect(() => { knowledgeApi.topics().then(setTopics).catch((caught) => {
    if (caught instanceof ApiError && caught.status === 401) onUnauthorized()
    else setError(caught instanceof Error ? caught.message : '주제를 불러오지 못했습니다.')
  }) }, [onUnauthorized])
  useEffect(() => {
    let active = true
    setTerms([]); setKeyterms([]); setDraft(emptyTerm); setAliasText(''); setEditingId(''); setMessage('')
    if (selected) {
      Promise.all([knowledgeApi.terms(selected), knowledgeApi.keyterms(selected)]).then(([list, approved]) => {
        if (active) { setTerms(list); setKeyterms(approved) }
      }).catch((caught) => { if (active) setError(caught instanceof Error ? caught.message : '사전을 불러오지 못했습니다.') })
    }
    return () => { active = false }
  }, [selected])

  async function reload() {
    const [list, approved] = await Promise.all([knowledgeApi.terms(selected), knowledgeApi.keyterms(selected)])
    setTerms(list); setKeyterms(approved)
  }
  async function act(action: () => Promise<unknown>, success: string) {
    setBusy(true); setError(''); setMessage('')
    try { await action(); setMessage(success) } catch (caught) { report(caught) }
    finally { setBusy(false) }
  }
  function choose(topic: Topic) { setSelected(topic.id); setName(topic.name); setDescription(topic.description ?? ''); setError('') }
  async function createTopic(event: FormEvent) {
    event.preventDefault()
    await act(async () => {
      const topic = await knowledgeApi.createTopic(newName.trim())
      setTopics(await knowledgeApi.topics()); choose(topic); setNewName('')
    }, '주제를 등록했습니다.')
  }
  async function saveTerm(event: FormEvent) {
    event.preventDefault()
    await act(async () => {
      const value = { ...draft, canonical: draft.canonical.trim(), aliases: aliasText.split(',').map((alias) => alias.trim()).filter(Boolean) }
      if (editingId) await knowledgeApi.editTerm(selected, editingId, value)
      else await knowledgeApi.createTerm(selected, value)
      await reload(); setDraft(emptyTerm); setAliasText(''); setEditingId('')
    }, '용어를 저장했습니다.')
  }

  return <main className="mx-auto max-w-7xl px-5 py-10 md:px-10">
    <AppLink className="text-sm font-bold text-moss underline" href="/meetings">회의 목록</AppLink>
    <h1 className="mt-6 text-3xl font-black">주제와 용어 사전</h1>
    <p className="mt-3 text-sm leading-6 text-slate-500">용어를 하나씩 검토해 승인하세요. 승인된 표준어만 최대 100개까지 전사에 사용할 후보가 됩니다. 같은 표준어를 연결한 주제는 수정과 승인 상태를 공유합니다. 현재 외부 AI 전송은 없습니다.</p>
    {error && <p role="alert" className="mt-4 rounded-xl bg-red-50 p-4 text-sm text-red-800">{error}</p>}
    {message && <p role="status" className="mt-4 text-sm font-bold text-moss">{message}</p>}
    <div className="mt-8 grid gap-6 lg:grid-cols-[17rem_1fr]">
      <aside className="space-y-3">
        <form onSubmit={createTopic} className="rounded-2xl bg-white p-4">
          <label htmlFor="new-topic" className="text-sm font-bold">새 주제</label>
          <input id="new-topic" className="field mt-2" maxLength={200} value={newName} onChange={(event) => setNewName(event.target.value)} />
          <button className="button-primary mt-3" disabled={busy || !newName.trim()}>주제 추가</button>
        </form>
        {topics.map((topic) => <button key={topic.id} disabled={busy} className={`block w-full rounded-2xl border p-4 text-left font-bold ${selected === topic.id ? 'border-moss bg-mint' : 'border-white bg-white'}`} onClick={() => choose(topic)}>{topic.name}</button>)}
      </aside>
      {selected ? <section className="space-y-5">
        <form className="rounded-2xl bg-white p-5" onSubmit={(event) => { event.preventDefault(); void act(async () => { await knowledgeApi.editTopic(selected, name.trim(), description); setTopics(await knowledgeApi.topics()) }, '주제를 수정했습니다.') }}>
          <label htmlFor="topic-name" className="text-sm font-bold">주제 이름</label><input id="topic-name" className="field mt-2" maxLength={200} value={name} onChange={(event) => setName(event.target.value)} />
          <label htmlFor="topic-description" className="mt-3 block text-sm font-bold">설명</label><textarea id="topic-description" className="field mt-2" maxLength={2000} value={description} onChange={(event) => setDescription(event.target.value)} />
          <button className="button-secondary mt-3" disabled={busy || !name.trim()}>주제 수정 저장</button>
          <button type="button" className="ml-3 text-sm text-red-700 underline" disabled={busy} onClick={() => { if (window.confirm('이 주제와 연결된 용어를 삭제할까요? 사용 중인 주제는 삭제할 수 없습니다.')) void act(async () => { await knowledgeApi.deleteTopic(selected); setSelected(''); setTopics(await knowledgeApi.topics()) }, '주제를 삭제했습니다.') }}>주제 삭제</button>
        </form>
        <div className="rounded-2xl bg-mint p-5 text-sm"><p className="font-black">승인된 keyterm 후보 {keyterms.length}/100</p><p className="mt-2 break-words text-moss">{keyterms.join(' · ') || '아직 승인한 용어가 없습니다.'}</p></div>
        <div className="rounded-2xl bg-white p-5">
          <h2 className="text-lg font-black">용어 목록</h2>
          <button className="button-secondary mt-3" disabled={busy} onClick={() => void act(async () => { await knowledgeApi.suggestTerms(selected); await reload() }, '가상 후보를 추가했습니다. 개별 승인 전에는 사용하지 않습니다.')}>가상 용어 후보 불러오기</button>
          <p className="mt-2 text-xs text-slate-500">MES/LOT 예제 데이터이며 실제 AI 추천이 아닙니다.</p>
          <div className="mt-4 space-y-3">{terms.map((term) => <article key={term.id} className="rounded-xl border border-moss/10 p-4" aria-label={`용어 ${term.canonical}`}>
            <p className="font-black">{term.canonical} <span className="text-xs font-semibold text-moss">{statusLabels[term.status]} · {term.source}</span></p>
            <p className="mt-1 text-sm text-slate-600">{term.meaning}</p><p className="mt-1 text-xs text-slate-500">별칭: {term.aliases.join(', ') || '없음'}</p>
            <div className="mt-3 flex flex-wrap gap-3 text-xs font-bold">
              {term.status !== 'APPROVED' && <button disabled={busy} className="text-moss underline" onClick={() => void act(async () => { await knowledgeApi.editTerm(selected, term.id, { ...term, status: 'APPROVED' }); await reload() }, '선택한 용어를 승인했습니다.')}>이 용어 승인</button>}
              {term.status !== 'REJECTED' && <button disabled={busy} className="text-slate-500 underline" onClick={() => void act(async () => { await knowledgeApi.editTerm(selected, term.id, { ...term, status: 'REJECTED' }); await reload() }, '선택한 용어를 반려했습니다.')}>반려</button>}
              <button disabled={busy} className="text-moss underline" onClick={() => { setEditingId(term.id); setDraft(term); setAliasText(term.aliases.join(', ')) }}>용어 수정</button>
              <button disabled={busy} className="text-red-700 underline" onClick={() => { if (window.confirm('이 용어를 삭제할까요?')) void act(async () => { await knowledgeApi.deleteTerm(selected, term.id); await reload() }, '용어를 삭제했습니다.') }}>용어 삭제</button>
            </div>
          </article>)}</div>
        </div>
        <form onSubmit={saveTerm} className="space-y-3 rounded-2xl bg-white p-5">
          <h2 className="text-lg font-black">{editingId ? '용어 수정' : '수동 용어 등록'}</h2>
          <label htmlFor="canonical" className="block text-sm font-bold">표준어</label><input id="canonical" className="field" maxLength={200} value={draft.canonical} onChange={(event) => setDraft({ ...draft, canonical: event.target.value })} />
          <label htmlFor="meaning" className="block text-sm font-bold">용어 뜻</label><textarea id="meaning" className="field" maxLength={2000} value={draft.meaning} onChange={(event) => setDraft({ ...draft, meaning: event.target.value })} />
          <label htmlFor="aliases" className="block text-sm font-bold">별칭 · 쉼표로 구분</label><input id="aliases" className="field" maxLength={4000} value={aliasText} onChange={(event) => setAliasText(event.target.value)} />
          <label htmlFor="term-status" className="block text-sm font-bold">용어 승인 상태</label><select id="term-status" className="field" value={draft.status} onChange={(event) => setDraft({ ...draft, status: event.target.value as Term['status'] })}><option value="PENDING">승인 대기</option><option value="APPROVED">검토 후 승인</option><option value="REJECTED">반려됨</option></select>
          <button className="button-primary" disabled={busy || !draft.canonical.trim() || !draft.meaning.trim()}>용어 저장</button>
          {editingId && <button type="button" className="button-secondary ml-2" onClick={() => { setEditingId(''); setDraft(emptyTerm); setAliasText('') }}>수정 취소</button>}
        </form>
      </section> : <p className="rounded-2xl bg-white p-8 text-slate-500">주제를 선택하거나 새 주제를 등록하세요.</p>}
    </div>
  </main>
}
