import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, api } from './api'
import type { SavedRecording } from './api'
import { acquireRecordingLock, MicrophoneRecording, syncRecording } from './recording'
import { recordingStore } from './recordingStore'
import type { LocalRecording } from './recordingStore'

interface RecordingPanelProps {
  meetingId: string
  isLive: boolean
  aiEnabled?: boolean
  onStreamStarted?: (stream: MediaStream) => Promise<void>
  aiConnection?: string
  onStreamStopping?: () => Promise<void>
  onFinished: () => Promise<void>
}

export function RecordingPanel({ meetingId, isLive, aiEnabled = false, aiConnection, onStreamStarted, onStreamStopping, onFinished }: RecordingPanelProps) {
  const [devices, setDevices] = useState<MediaDeviceInfo[]>([])
  const [deviceId, setDeviceId] = useState('')
  const [acknowledged, setAcknowledged] = useState(false)
  const [recording, setRecording] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [local, setLocal] = useState<LocalRecording>()
  const [saved, setSaved] = useState<SavedRecording>()
  const [pending, setPending] = useState(0)
  const engine = useRef<MicrophoneRecording>()
  const release = useRef<() => void>()
  const microphone = useRef<MediaStream>()
  const syncing = useRef(false)
  const alive = useRef(true)
  const finished = useRef(onFinished)
  finished.current = onFinished
  const transport = useCallback(() => ({
    upload: api.uploadChunk,
    complete: (count: number) => api.completeRecording(meetingId, count),
  }), [meetingId])

  const refresh = useCallback(async () => {
    const [session, chunks, server] = await Promise.all([
      recordingStore.get(meetingId), recordingStore.pending(meetingId),
      api.getRecording(meetingId).catch((error) => { if (error instanceof ApiError && error.status === 404) return undefined; throw error }),
    ])
    if (alive.current) { setLocal(session); setPending(chunks.length); setSaved(server) }
  }, [meetingId])

  const retry = useCallback(async () => {
    if (syncing.current) return
    syncing.current = true
    let unlock: (() => void) | undefined
    try {
      if (engine.current) {
        await engine.current.sync()
        if (alive.current) setRecording(engine.current.isActive())
      }
      else {
        unlock = await acquireRecordingLock(meetingId)
        const session = await recordingStore.get(meetingId)
        if (session) {
          await recordingStore.save({ ...session, stopped: !isLive })
          const complete = await syncRecording(meetingId, recordingStore, transport())
          if (alive.current) {
            setMessage(complete ? '보관한 청크 복구 완료' : '청크 동기화 완료 · 마이크를 선택해 녹음을 재개할 수 있습니다.')
            if (complete) await finished.current()
          }
        }
      }
      await refresh()
    } catch (error) { if (alive.current) setMessage(error instanceof Error ? error.message : '업로드를 다시 시도해 주세요.') }
    finally { unlock?.(); syncing.current = false }
  }, [isLive, meetingId, refresh, transport])

  useEffect(() => {
    alive.current = true
    void refresh().catch(() => setMessage('녹음 저장소를 불러오지 못했습니다.'))
    const updateDevices = () => {
      void navigator.mediaDevices?.enumerateDevices().then((list) => {
        if (alive.current) setDevices(list.filter((device) => device.kind === 'audioinput'))
      }).catch(() => { if (alive.current) setMessage('마이크 목록을 읽지 못했습니다. 권한을 확인하세요.') })
    }
    updateDevices()
    navigator.mediaDevices?.addEventListener('devicechange', updateDevices)
    const timer = window.setInterval(() => { if (engine.current) void retry() }, 5000)
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (engine.current) { event.preventDefault(); event.returnValue = '' }
    }
    window.addEventListener('online', retry)
    window.addEventListener('beforeunload', beforeUnload)
    return () => {
      alive.current = false
      window.clearInterval(timer)
      window.removeEventListener('online', retry)
      window.removeEventListener('beforeunload', beforeUnload)
      navigator.mediaDevices?.removeEventListener('devicechange', updateDevices)
      const current = engine.current
      const unlock = release.current
      if (current) void Promise.resolve().then(() => onStreamStopping?.()).catch(() => {}).then(() => current.stop(false)).catch(() => {}).finally(() => unlock?.())
      else unlock?.()
    }
  }, [meetingId, onStreamStopping, refresh, retry])

  async function start() {
    setBusy(true)
    setMessage('마이크 권한 확인 중')
    let stream: MediaStream | undefined
    try {
      if (syncing.current) throw new Error('청크 동기화가 끝난 뒤 다시 시도하세요.')
      if (!navigator.mediaDevices || typeof MediaRecorder === 'undefined') throw new Error('HTTPS 또는 localhost에서 최신 브라우저를 사용하세요.')
      release.current = await acquireRecordingLock(meetingId)
      const previous = await recordingStore.get(meetingId)
      if (previous?.stopped) throw new Error('종료한 녹음은 청크 복구로 저장을 완료하세요.')
      if (previous && !previous.mimeType.startsWith('audio/webm')) throw new Error('이 형식은 이어 녹음을 지원하지 않습니다. 기존 녹음을 저장하고 새 회의를 시작하세요.')
      const space = await navigator.storage?.estimate()
      if (space?.quota && space.quota - (space.usage ?? 0) < 10 * 1024 * 1024) throw new Error('브라우저 녹음 공간이 부족합니다. 보관한 청크를 복구하고 공간을 확보하세요.')
      stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, sampleRate: 48000, ...(deviceId ? { deviceId: { exact: deviceId } } : {}) } })
      if (!alive.current) throw new Error('녹음 화면이 닫혔습니다.')
      const mimeType = (previous ? [previous.mimeType] : ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus']).find((type) => MediaRecorder.isTypeSupported(type))
      if (!mimeType) throw new Error('지원되는 녹음 형식이 없습니다. Chrome을 사용하세요.')
      const recorder = new MediaRecorder(stream, { mimeType, audioBitsPerSecond: 64_000 })
      const current = new MicrophoneRecording(recorder, stream, meetingId, recordingStore, transport(), (text) => { if (alive.current) setMessage(text) }, previous)
      const streamError = await current.start(aiEnabled ? onStreamStarted : undefined)
      microphone.current = stream
      engine.current = current
      setRecording(true)
      setMessage(streamError ? `녹음은 계속됩니다 · 실시간 AI 연결 실패: ${streamError.message}` : aiEnabled ? '녹음 중 · AI 연결 상태는 상단에서 확인하세요.' : '녹음 중 · 브라우저 청크 보관')
      setDevices((await navigator.mediaDevices.enumerateDevices()).filter((device) => device.kind === 'audioinput'))
    } catch (error) {
      stream?.getTracks().forEach((track) => track.stop())
      release.current?.(); release.current = undefined
      setMessage(error instanceof Error ? error.message : '마이크를 사용할 수 없습니다.')
    } finally { setBusy(false) }
  }

  async function reconnectAi() {
    if (!microphone.current || !onStreamStarted) return
    setBusy(true)
    try {
      await onStreamStarted(microphone.current)
    } catch (error) {
      setMessage(`녹음은 계속됩니다 · ${error instanceof Error ? error.message : 'AI 재연결에 실패했습니다.'}`)
    } finally { setBusy(false) }
  }

  async function stop() {
    setBusy(true)
    if (!engine.current) {
      try { release.current = await acquireRecordingLock(meetingId) }
      catch (error) { setMessage(error instanceof Error ? error.message : '다른 탭을 확인하세요.'); setBusy(false); return }
    }
    try {
      if (aiEnabled && onStreamStopping) {
        try { await onStreamStopping() }
        catch (caught) { setMessage(`AI 종료 확인 실패 · 원본 녹음은 보존합니다: ${caught instanceof Error ? caught.message : '연결 상태를 확인하세요.'}`) }
      }
      if (engine.current) await engine.current.stop()
      else {
        const session = await recordingStore.get(meetingId)
        if (session) {
          await recordingStore.save({ ...session, stopped: true })
          await syncRecording(meetingId, recordingStore, transport())
        }
      }
    } catch (error) { setMessage(error instanceof Error ? error.message : '저장 상태를 확인하세요.') }
    finally {
      microphone.current = undefined
      engine.current = undefined
      release.current?.(); release.current = undefined
      setRecording(false)
      try { await refresh(); await onFinished() }
      catch (error) { setMessage(error instanceof Error ? error.message : '저장 상태를 확인하세요.') }
      setBusy(false)
    }
  }

  return (
    <section className="mt-6 rounded-3xl border border-moss/15 bg-white p-6" aria-label="마이크 녹음">
      <h2 className="text-xl font-black">마이크 녹음</h2>
      <p className="mt-2 text-sm leading-6 text-slate-500">원본 음성은 이 브라우저와 개인 서버에 독립적으로 저장됩니다. {aiEnabled ? '실시간 전사 장애가 발생해도 원본 녹음은 계속됩니다.' : '외부 AI로 전송하지 않습니다.'} 화면을 닫으면 녹음은 중지되며 저장된 청크는 복구할 수 있습니다.</p>
      {!recording && local && !local.stopped && isLive && <p className="mt-4 text-sm text-amber-900">중단 전 청크를 보존한 채 같은 회의에서 이어 녹음합니다. 중단된 동안의 소리는 복구되지 않습니다.</p>}
      {!recording && !local?.stopped && saved?.state !== 'COMPLETED' && isLive && <>
        <label className="mt-4 block text-sm font-bold" htmlFor="microphone">마이크 선택</label>
        <select id="microphone" className="field mt-2" value={deviceId} onChange={(event) => setDeviceId(event.target.value)} disabled={busy}>
          <option value="">기본 마이크</option>
          {devices.map((device, index) => <option key={device.deviceId || index} value={device.deviceId}>{device.label || `마이크 ${index + 1}`}</option>)}
        </select>
        <label className="mt-4 flex gap-2 text-sm"><input type="checkbox" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)} />참여자에게 녹음을 알렸고 녹음에 동의합니다.</label>
        <button className="button-primary mt-4" disabled={!acknowledged || busy} onClick={() => void start()}>{local ? '청크 복구 후 녹음 재개' : '마이크 녹음 시작'}</button>
      </>}
      {recording && <button className="button-stop mt-4" disabled={busy} onClick={() => void stop()}>녹음 종료 및 저장</button>}
      {!recording && isLive && <button className="button-secondary ml-3 mt-4" disabled={busy} onClick={() => void stop()}>회의 종료</button>}
      {recording && aiEnabled && aiConnection !== 'connected' && <button className="button-secondary ml-3 mt-4" disabled={busy || aiConnection === 'connecting'} onClick={() => void reconnectAi()}>AI 전사 다시 연결</button>}
      {recording && aiEnabled && <p className="mt-3 text-sm text-slate-500">AI 재연결은 녹음을 유지하며, 연결된 시점부터 자막을 생성합니다. 연결되지 않은 구간은 소급 전사하지 않습니다.</p>}
      <p role="status" className="mt-4 text-sm font-semibold">{message}</p>
      {local && <div className="mt-3 rounded-xl bg-cream p-4 text-sm">
        <p>브라우저 보관: {local.chunkCount}개 청크 · {Math.round(local.durationMs / 1000)}초 · 업로드 대기 {pending}개</p>
        {!recording && <button className="button-secondary mt-3" onClick={() => void retry()}>보관한 청크 복구</button>}
      </div>}
      {saved?.state === 'COMPLETED' && <div className="mt-4 space-y-3">
        <p className="text-sm font-bold">서버 저장 완료 · {Math.round((saved.bytes ?? 0) / 1024)} KB</p>
        <audio aria-label="저장된 녹음 재생" controls preload="metadata" src={`/api/v1/meetings/${meetingId}/recording/audio`} className="w-full" />
        <a className="button-secondary inline-flex" href={`/api/v1/meetings/${meetingId}/recording/audio`} download>원본 녹음 다운로드</a>
      </div>}
    </section>
  )
}
