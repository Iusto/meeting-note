import { useEffect, useState } from 'react'

function SpeakerNameInput({ label, name, fallback, save }: { label: string; name: string; fallback: string; save: (label: string, name: string, expected: string) => Promise<void> }) {
  const [draft, setDraft] = useState(name)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => { setDraft(name) }, [name])
  return <form className="space-y-2" onSubmit={async event => {
    event.preventDefault(); setBusy(true); setError('')
    try { await save(label, draft.trim(), name) }
    catch (caught) { setError(caught instanceof Error ? caught.message : '이름을 저장하지 못했습니다.') }
    finally { setBusy(false) }
  }}>
    <label className="block text-sm font-bold">{fallback}<input className="field mt-1" maxLength={200} placeholder="이름 또는 역할 입력" value={draft} onChange={event => setDraft(event.target.value)} disabled={busy} /></label>
    <button className="text-xs font-bold text-moss underline" disabled={busy || draft.trim() === name}>{busy ? '저장 중…' : draft.trim() ? '이름 저장' : '기본 이름으로 복원'}</button>
    {error && <p role="alert" className="text-xs text-red-700">{error}</p>}
  </form>
}

function SpeakerMerge({ label, labels, names, merges, fallback, merge }: { label: string; labels: string[]; names: Record<string, string>; merges: Record<string, string>; fallback: (label: string) => string; merge: (label: string, target: string | null) => Promise<void> }) {
  const [target, setTarget] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const root = merges[label] ?? label
  const candidates = labels.filter(value => !merges[value] && value !== root)
  async function apply(value: string | null) {
    setBusy(true); setError('')
    try { await merge(label, value); setTarget('') }
    catch (caught) { setError(caught instanceof Error ? caught.message : '화자를 연결하지 못했습니다.') }
    finally { setBusy(false) }
  }
  return <div className="mt-2 space-y-2">
    {merges[label] ? <><p className="text-xs text-moss">{names[root] || fallback(root)}와 같은 화자로 표시 중</p><button type="button" disabled={busy} className="text-xs font-bold text-moss underline" onClick={() => void apply(null)}>이 화자 분리하기</button></> : <form className="flex flex-wrap gap-2" onSubmit={event => { event.preventDefault(); if (target) void apply(target) }}>
      <select aria-label={`${fallback(label)} 합칠 대상`} className="field min-w-0 flex-1 text-xs" value={target} disabled={busy} onChange={event => setTarget(event.target.value)}><option value="">같은 사람 선택</option>{candidates.map(value => <option key={value} value={value}>{fallback(value)}{names[value] ? ` · ${names[value]}` : ''}</option>)}</select>
      <button className="text-xs font-bold text-moss underline" disabled={busy || !target}>화자 합치기</button>
    </form>}
    {error && <p role="alert" className="text-xs text-red-700">{error}</p>}
  </div>
}

export function SpeakerNames({ labels, names, merges, fallback, save, merge }: { labels: string[]; names: Record<string, string>; merges: Record<string, string>; fallback: (label: string) => string; save: (label: string, name: string, expected: string) => Promise<void>; merge: (label: string, target: string | null) => Promise<void> }) {
  return <details open className="rounded-2xl border border-moss/10 bg-white p-5">
    <summary className="cursor-pointer font-bold">화자 이름 설정</summary>
    <p className="mt-3 text-xs leading-5 text-slate-500">녹음 중에도 이름을 지정하거나 같은 사람을 합칠 수 있습니다. 합치면 선택한 사람의 이름으로 기존·후속 발언을 함께 표시합니다. 이미 합친 그룹을 다른 그룹에 합치면 구성원 전체가 이동합니다. 분리하면 해당 화자만 원래 이름으로 돌아갑니다. 원문과 원래 화자 정보는 보존됩니다.</p>
    <div className="mt-4 space-y-4">{labels.map(label => <div key={label} className="border-b border-moss/10 pb-4">
      {merges[label] ? <p className="text-sm font-bold">{fallback(label)}</p> : <SpeakerNameInput label={label} name={names[label] ?? ''} fallback={fallback(label)} save={save} />}
      <SpeakerMerge label={label} labels={labels} names={names} merges={merges} fallback={fallback} merge={merge} />
    </div>)}</div>
    {labels.length === 0 && <p className="mt-3 text-sm text-slate-500">화자가 감지되면 여기에 표시됩니다.</p>}
  </details>
}
