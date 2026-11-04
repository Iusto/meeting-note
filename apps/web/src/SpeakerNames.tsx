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

export function SpeakerNames({ labels, names, fallback, save }: { labels: string[]; names: Record<string, string>; fallback: (label: string) => string; save: (label: string, name: string, expected: string) => Promise<void> }) {
  return <details open className="rounded-2xl border border-moss/10 bg-white p-5">
    <summary className="cursor-pointer font-bold">화자 이름 설정</summary>
    <p className="mt-3 text-xs leading-5 text-slate-500">녹음 중에도 이름이나 역할을 지정할 수 있습니다. 같은 화자의 발언에 바로 적용되며 원문은 유지됩니다. 재연결 후 새 화자가 생기면 따로 지정하세요.</p>
    <div className="mt-4 space-y-4">{labels.map(label => <SpeakerNameInput key={label} label={label} name={names[label] ?? ''} fallback={fallback(label)} save={save} />)}</div>
    {labels.length === 0 && <p className="mt-3 text-sm text-slate-500">화자가 감지되면 여기에 표시됩니다.</p>}
  </details>
}
