import { useState } from 'react'

export function SegmentSpeaker({ originalName, currentLabel, options, save }: { originalName: string; currentLabel?: string; options: { label: string; name: string }[]; save: (label: string | null) => Promise<void> }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  return <details className="px-5 pb-3 text-xs text-slate-500">
    <summary className="cursor-pointer">이 발언의 화자 수정{currentLabel ? ' · 사용자 지정됨' : ''}</summary>
    <p className="mt-2">자동 인식: {originalName}. 이 발언에만 적용됩니다.</p>
    <label className="mt-2 block">실제 화자<select aria-label="이 발언의 실제 화자" className="field mt-1 text-xs" value={currentLabel ?? ''} disabled={busy} onChange={async event => {
      const value = event.target.value; setBusy(true); setError('')
      try { await save(value || null) }
      catch(caught) { setError(caught instanceof Error ? caught.message : '화자를 수정하지 못했습니다.') }
      finally { setBusy(false) }
    }}><option value="">자동 인식으로 복원</option>{options.map(option => <option key={option.label} value={option.label}>{option.name}</option>)}</select></label>
    {error && <p role="alert" className="mt-2 text-red-700">{error}</p>}
  </details>
}
