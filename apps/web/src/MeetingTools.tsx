import { useState } from 'react'
import { ApiError, api } from './api'
import type { Meeting } from './types'
import { acquireRecordingLock } from './recording'
import { removeLocalRecording } from './recordingStore'
import { AppLink, navigate } from './router'

export function MeetingTools({ meeting, onChanged, onUnauthorized }: { meeting: Meeting; onChanged: (meeting: Meeting) => void; onUnauthorized: () => void }) {
  const [editing, setEditing] = useState(false)
  const [title, setTitle] = useState('')
  const [revision, setRevision] = useState(meeting.revision)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [confirmation, setConfirmation] = useState('')
  const [deleteRevision, setDeleteRevision] = useState(meeting.revision)
  const [deleteTitle, setDeleteTitle] = useState('')
  const [deleted, setDeleted] = useState(false)

  async function act(action: () => Promise<void>) {
    setBusy(true); setError(''); setNotice('')
    try { await action() }
    catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) onUnauthorized()
      else if (caught instanceof ApiError && caught.code === 'STALE_REVISION') setError('회의가 변경되었습니다. 초안을 보관한 후 새로고침하여 최신 제목을 확인하세요.')
      else if (caught instanceof ApiError && caught.code === 'MEETING_ACTIVE') setError('진행 중인 회의는 삭제할 수 없습니다. 회의를 종료한 뒤 다시 확인하세요.')
      else if (caught instanceof ApiError && caught.code === 'RECORDING_STORAGE_UNSAFE') setError('녹음 파일의 안전 검사를 통과하지 못해 삭제하지 않았습니다. 서버의 녹음 저장 상태를 확인하세요.')
      else setError(caught instanceof Error ? caught.message : '요청을 처리하지 못했습니다.')
    } finally { setBusy(false) }
  }

  async function download(format: 'json' | 'markdown') {
    const response = await fetch(`/api/v1/meetings/${meeting.id}/export?format=${format}`, { credentials: 'include' })
    if (!response.ok) throw new ApiError('내보내기에 실패했습니다. 다시 시도하세요.', response.status)
    const url = URL.createObjectURL(await response.blob())
    const link = document.createElement('a')
    link.href = url; link.download = `meeting-${meeting.id}.${format === 'json' ? 'json' : 'md'}`
    document.body.append(link); link.click(); link.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    setNotice('회의 파일을 내보냈습니다.')
  }

  async function deleteMeeting() {
    if (confirmation !== deleteTitle) return
    const release = await acquireRecordingLock(meeting.id)
    try {
      const result = await api.deleteMeeting(meeting.id, deleteRevision)
      setDeleted(true)
      try { await removeLocalRecording(meeting.id) }
      catch {
        setNotice('서버의 회의는 삭제했습니다. 이 브라우저의 복구용 청크를 지우지 못했습니다. 브라우저 사이트 데이터도 확인하세요.')
        return
      }
      if (result.cleanupPending) setNotice('회의 기록은 삭제했습니다. 서버 녹음 파일 정리가 남아 있습니다. 서버 재시작 시 다시 정리합니다.')
      else navigate('/meetings')
    } finally { release() }
  }

  if (deleted) return <section className="mt-4 rounded-2xl border border-moss/10 bg-white p-5" aria-label="삭제 결과"><p role="status" className="text-sm text-amber-800">{notice || '회의를 삭제했습니다.'}</p><AppLink href="/meetings" className="button-secondary mt-3 inline-flex">회의 목록으로</AppLink></section>

  return <section aria-label="회의 관리" className="mt-4 rounded-2xl border border-moss/10 bg-white p-5">
    <div className="flex flex-wrap gap-3">
      {!editing && <button className="button-secondary" disabled={busy} onClick={() => { setTitle(meeting.title); setRevision(meeting.revision); setEditing(true); setConfirmingDelete(false); setError(''); setNotice('') }}>제목 수정</button>}
      <button className="button-secondary" disabled={busy} onClick={() => void act(() => download('markdown'))}>Markdown 내보내기</button>
      <button className="button-secondary" disabled={busy} onClick={() => void act(() => download('json'))}>JSON 내보내기</button>
      <button className="button-secondary text-red-700" disabled={busy || meeting.status === 'STARTED'} onClick={() => { setDeleteTitle(meeting.title); setDeleteRevision(meeting.revision); setConfirmation(''); setConfirmingDelete(true); setEditing(false); setError(''); setNotice('') }}>회의 삭제</button>
    </div>
    {editing && <form className="mt-4 space-y-3" onSubmit={(event) => { event.preventDefault(); void act(async () => { const updated = await api.updateMeeting(meeting.id, title.trim(), revision); onChanged(updated); setEditing(false); setNotice('제목을 저장했습니다.') }) }}>
      <label htmlFor="edit-meeting-title" className="block text-sm font-bold">새 회의 제목</label>
      <input id="edit-meeting-title" className="field" value={title} maxLength={200} required disabled={busy} onChange={(event) => setTitle(event.target.value)} />
      <button className="button-primary" disabled={busy || !title.trim()}>제목 저장</button>
      <button type="button" className="button-secondary ml-2" disabled={busy} onClick={() => setEditing(false)}>취소</button>
    </form>}
    {confirmingDelete && <form className="mt-4 space-y-3 rounded-xl border border-red-200 p-4" onSubmit={(event) => { event.preventDefault(); void act(deleteMeeting) }}>
      <p className="text-sm leading-6 text-red-800">이 회의의 녹음·원문·수정 이력·요약과 이 브라우저의 복구용 청크를 삭제합니다. 되돌릴 수 없습니다. 다른 브라우저와 백업의 사본은 별도로 관리하세요.</p>
      <label htmlFor="delete-meeting-confirmation" className="block text-sm font-bold">삭제할 회의 제목 입력: {deleteTitle}</label>
      <input id="delete-meeting-confirmation" className="field" value={confirmation} required disabled={busy} autoComplete="off" onChange={(event) => setConfirmation(event.target.value)} />
      <button className="button-primary bg-red-700" disabled={busy || confirmation !== deleteTitle}>영구 삭제</button>
      <button type="button" className="button-secondary ml-2" disabled={busy} onClick={() => setConfirmingDelete(false)}>삭제 취소</button>
    </form>}
    {error && <p role="alert" className="mt-3 text-sm text-red-700">{error}</p>}
    {notice && <p role="status" className="mt-3 text-sm text-moss">{notice}</p>}
    <p className="mt-3 text-xs text-slate-500">내보낸 파일에는 저장된 원문·교정문과 요약 근거가 포함됩니다. 녹음 파일은 별도로 다운로드하세요.</p>
  </section>
}
