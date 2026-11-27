import { useEffect, useState } from 'react'
import { ApiError, api } from './api'
import type { RecordingImportStatus } from './types'

const labels: Record<RecordingImportStatus['state'], string> = {
  QUEUED: '녹음 파일 저장 완료 · 전사 대기 중',
  TRANSCRIBING: '녹음 파일 전사 중',
  TRANSCRIPTION_FAILED: '전사를 완료하지 못했습니다',
  SUMMARY_QUEUED: '전사 완료 · 요약 대기 중',
  SUMMARIZING: 'AI 요약과 회의록 작성 중',
  SUMMARY_FAILED: '전사 완료 · 요약 생성 실패',
  COMPLETED: 'AI 요약과 회의록 완성',
}

export function RecordingImportPanel({ meetingId, onUnauthorized, onState, onTranscriptReady }: {
  meetingId: string
  onUnauthorized: () => void
  onState: (state: RecordingImportStatus['state']) => void
  onTranscriptReady: () => Promise<void>
}) {
  const [status, setStatus] = useState<RecordingImportStatus>()
  const [error, setError] = useState('')
  const [retrying, setRetrying] = useState(false)
  const [retryVersion, setRetryVersion] = useState(0)
  const failed = status?.state === 'TRANSCRIPTION_FAILED' || status?.state === 'SUMMARY_FAILED'

  useEffect(() => {
    let active = true
    let transcriptLoaded = false
    let timer: number
    const poll = async () => {
      try {
        const value = await api.recordingImport(meetingId)
        if (!active) return
        setStatus(value)
        onState(value.state)
        if (!transcriptLoaded && ['SUMMARY_QUEUED', 'SUMMARIZING', 'SUMMARY_FAILED', 'COMPLETED'].includes(value.state)) {
          await onTranscriptReady()
          transcriptLoaded = true
        }
        if (!active) return
        setError('')
        if (value.state === 'COMPLETED' || value.state === 'TRANSCRIPTION_FAILED' || value.state === 'SUMMARY_FAILED') return
      } catch (caught) {
        if (!active) return
        if (caught instanceof ApiError && caught.status === 401) { onUnauthorized(); return }
        setError('처리 상태를 불러오지 못했습니다. 다시 연결하는 중입니다.')
      }
      timer = window.setTimeout(poll, 1500)
    }
    void poll()
    return () => { active = false; window.clearTimeout(timer) }
  }, [meetingId, onState, onTranscriptReady, onUnauthorized, retryVersion])

  async function retry() {
    setRetrying(true)
    setError('')
    try {
      const next = await api.retryRecordingImport(meetingId)
      setStatus(next)
      onState(next.state)
      setRetryVersion(value => value + 1)
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) onUnauthorized()
      else setError(caught instanceof Error ? caught.message : '다시 시작하지 못했습니다.')
    } finally {
      setRetrying(false)
    }
  }

  return <section className="mt-4 space-y-3 rounded-2xl border border-moss/10 bg-white p-5" aria-label="녹음 파일 처리">
    <p role="status" className="font-bold text-moss">{status ? labels[status.state] : '녹음 파일의 처리 상태를 확인 중입니다'}</p>
    {(error || status?.error) && <p role="alert" className="text-sm text-red-700">{error || status?.error}</p>}
    <div className="flex flex-wrap gap-3">
      <a className="button-secondary" href={`/api/v1/meetings/${meetingId}/recording/audio`}>원본 녹음 다운로드</a>
      {failed && <button className="button-secondary" disabled={retrying} onClick={() => void retry()}>{status.state === 'SUMMARY_FAILED' ? '요약 다시 시도' : '전사 다시 시도'}</button>}
    </div>
  </section>
}
