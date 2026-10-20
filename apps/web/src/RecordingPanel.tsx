import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, api } from './api'
import type { SavedRecording } from './api'
import { acquireRecordingLock, MicrophoneRecording, syncRecording } from './recording'
import { recordingStore } from './recordingStore'
import type { LocalRecording } from './recordingStore'

export function RecordingPanel({ meetingId, isLive, onFinished }: { meetingId: string; isLive: boolean; onFinished: () => Promise<void> }) {
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
          await recordingStore.save({ ...session, stopped: true })
          const complete = await syncRecording(meetingId, recordingStore, transport())
          if (complete && alive.current) { setMessage('보관한 청크 복구 완료'); await finished.current() }
        }
      }
      await refresh()
    } catch (error) { if (alive.current) setMessage(error instanceof Error ? error.message : '업로드를 다시 시도해 주세요.') }
    finally { unlock?.(); syncing.current = false }
  }, [meetingId, refresh, transport])

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
      if (current) void current.stop().then(() => api.stopMeeting(meetingId)).catch(() => {}).finally(() => unlock?.())
      else unlock?.()
    }
  }, [meetingId, refresh, retry])

  async function start() {
    setBusy(true)
    setMessage('마이크 권한 확인 중')
    let stream: MediaStream | undefined
    try {
      if (!navigator.mediaDevices || typeof MediaRecorder === 'undefined') throw new Error('HTTPS 또는 localhost에서 최신 브라우저를 사용하세요.')
      release.current = await acquireRecordingLock(meetingId)
      if (await recordingStore.get(meetingId)) throw new Error('이전 녹음의 청크부터 복구하세요.')
      const space = await navigator.storage?.estimate()
      if (space?.quota && space.quota - (space.usage ?? 0) < 10 * 1024 * 1024) throw new Error('브라우저 녹음 공간이 부족합니다. 보관한 청크를 복구하고 공간을 확보하세요.')
      stream = await navigator.mediaDevices.getUserMedia({ audio: deviceId ? { deviceId: { exact: deviceId } } : true })
      if (!alive.current) throw new Error('녹음 화면이 닫혔습니다.')
      const mimeType = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus'].find((type) => MediaRecorder.isTypeSupported(type))
      if (!mimeType) throw new Error('지원되는 녹음 형식이 없습니다. Chrome을 사용하세요.')
      const recorder = new MediaRecorder(stream, { mimeType, audioBitsPerSecond: 64_000 })
      const current = new MicrophoneRecording(recorder, stream, meetingId, recordingStore, transport(), (text) => { if (alive.current) setMessage(text) })
      await current.start()
      engine.current = current
      setRecording(true)
      setMessage('녹음 중 · 브라우저 청크 보관')
      setDevices((await navigator.mediaDevices.enumerateDevices()).filter((device) => device.kind === 'audioinput'))
    } catch (error) {
      stream?.getTracks().forEach((track) => track.stop())
      release.current?.(); release.current = undefined
      setMessage(error instanceof Error ? error.message : '마이크를 사용할 수 없습니다.')
    } finally { setBusy(false) }
  }

  async function stop() {
    setBusy(true)
    if (!engine.current) {
      try { release.current = await acquireRecordingLock(meetingId) }
      catch (error) { setMessage(error instanceof Error ? error.message : '다른 탭을 확인하세요.'); setBusy(false); return }
    }
    try {
      await engine.current?.stop()
    } catch (error) { setMessage(error instanceof Error ? error.message : '저장 상태를 확인하세요.') }
    finally {
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
      <p className="mt-2 text-sm leading-6 text-slate-500">음성은 이 브라우저와 개인 서버에만 저장됩니다. API 연결 전에는 자막이 생성되지 않습니다. 화면을 닫으면 녹음은 중지되며 저장된 청크는 복구할 수 있습니다.</p>
      {!recording && !local && saved?.state !== 'COMPLETED' && isLive && <>
        <label className="mt-4 block text-sm font-bold" htmlFor="microphone">마이크 선택</label>
        <select id="microphone" className="field mt-2" value={deviceId} onChange={(event) => setDeviceId(event.target.value)} disabled={busy}>
          <option value="">기본 마이크</option>
          {devices.map((device, index) => <option key={device.deviceId || index} value={device.deviceId}>{device.label || `마이크 ${index + 1}`}</option>)}
        </select>
        <label className="mt-4 flex gap-2 text-sm"><input type="checkbox" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)} />참여자에게 녹음을 알렸고 녹음에 동의합니다.</label>
        <button className="button-primary mt-4" disabled={!acknowledged || busy} onClick={() => void start()}>마이크 녹음 시작</button>
      </>}
      {recording && <button className="button-stop mt-4" disabled={busy} onClick={() => void stop()}>녹음 종료 및 저장</button>}
      {!recording && isLive && <button className="button-secondary ml-3 mt-4" disabled={busy} onClick={() => void stop()}>회의 종료</button>}
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
