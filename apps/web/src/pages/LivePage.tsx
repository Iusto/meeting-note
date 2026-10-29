import { useCallback, useEffect, useReducer, useRef, useState } from 'react'
import { AlertTriangle, ArrowLeft, CircleStop, LoaderCircle, Radio, RefreshCw, Wifi, WifiOff } from '../icons'
import { ApiError, api, liveSocketUrl } from '../api'
import { LivePcmTransport, MicrophonePcmCapture } from '../liveAudio'
import { MockNotice, TranscriptCard } from '../components'
import { liveReducer, initialLiveState } from '../liveReducer'
import { AppLink } from '../router'
import type { AiConfiguration, LiveEvent, Meeting } from '../types'
import { RecordingPanel } from '../RecordingPanel'
import { SummaryPanel } from '../SummaryPanel'
import { MeetingTools } from '../MeetingTools'

const connectionCopy = {
  idle: ['대기 중', 'bg-slate-100 text-slate-600'],
  connecting: ['연결 중', 'bg-amber-100 text-amber-800'],
  connected: ['가상 스트림 연결됨', 'bg-mint text-moss'],
  reconnecting: ['다시 연결 중', 'bg-amber-100 text-amber-800'],
  stopped: ['종료됨', 'bg-slate-100 text-slate-600'],
  error: ['연결 오류', 'bg-red-100 text-red-800'],
} as const

export function LivePage({ meetingId, onUnauthorized }: { meetingId: string; onUnauthorized: () => void }) {
  const [meeting, setMeeting] = useState<Meeting | null>(null)
  const [state, dispatch] = useReducer(liveReducer, initialLiveState)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [audioGaps, setAudioGaps] = useState<{ startMs: number; endMs: number }[]>([])
  const [stopping, setStopping] = useState(false)
  const [aiConfiguration, setAiConfiguration] = useState<AiConfiguration>()
  const socketRef = useRef<WebSocket | null>(null)
  const liveTransportRef = useRef<LivePcmTransport>()
  const pcmCaptureRef = useRef<MicrophonePcmCapture>()
  const retryRef = useRef<number | undefined>(undefined)
  const intentionallyClosedRef = useRef(false)
  const meetingStartedAtRef = useRef<string | null>()

  const loadTranscript = useCallback(async () => {
    const segments = await api.getTranscript(meetingId)
    dispatch({ type: 'hydrate', segments })
  }, [meetingId])

  const stopLiveAi = useCallback(async () => {
    const capture = pcmCaptureRef.current
    const transport = liveTransportRef.current
    pcmCaptureRef.current = undefined
    liveTransportRef.current = undefined
    try {
      await capture?.flush()
      await transport?.stop()
    } finally {
      await capture?.close()
    }
  }, [])

  const startLiveAi = useCallback(async (stream: MediaStream) => {
    await stopLiveAi()
    const configuration = await api.getAiConfiguration()
    setAiConfiguration(configuration)
    const startedAt = meetingStartedAtRef.current ? Date.parse(meetingStartedAtRef.current) : Number.NaN
    const baseOffsetMs = Number.isFinite(startedAt) ? Math.max(0, Date.now() - startedAt) : 0
    const transport = new LivePcmTransport({
      meetingId,
      baseOffsetMs,
      socketUrl: liveSocketUrl,
      onConnecting: (reconnecting) => dispatch({ type: 'connecting', reconnecting }),
      onEvent: (event) => dispatch({ type: 'event', event }),
      onDropped: ({ startMs, endMs }) => setAudioGaps(previous => {
        const last = previous.at(-1)
        return last && last.endMs >= startMs
          ? [...previous.slice(0, -1), { startMs: last.startMs, endMs: Math.max(last.endMs, endMs) }]
          : [...previous, { startMs, endMs }]
      }),
    })
    const capture = new MicrophonePcmCapture((pcm) => transport.accept(pcm))
    liveTransportRef.current = transport
    pcmCaptureRef.current = capture
    transport.start()
    try { await capture.start(stream) }
    catch (caught) {
      await stopLiveAi()
      dispatch({ type: 'event', event: { type: 'stream.error', meetingId, message: 'AI 음성 입력을 시작하지 못했습니다. 다시 연결하세요.' } })
      throw caught
    }
  }, [meetingId, stopLiveAi])

  useEffect(() => {
    let active = true
    intentionallyClosedRef.current = false

    function connect(reconnecting = false) {
      if (!active || intentionallyClosedRef.current) return
      dispatch({ type: 'connecting', reconnecting })
      const socket = new WebSocket(liveSocketUrl(meetingId))
      socketRef.current = socket
      socket.onmessage = (message) => {
        try {
          const liveEvent = JSON.parse(message.data) as LiveEvent
          if (liveEvent.type === 'stream.stopped') {
            intentionallyClosedRef.current = true
            void (async () => {
              let updatedMeeting = await api.getMeeting(meetingId)
              for (let attempt = 0; attempt < 5; attempt += 1) {
                await loadTranscript()
                if (updatedMeeting.status !== 'STARTED') break
                await new Promise((resolve) => window.setTimeout(resolve, 300))
                updatedMeeting = await api.getMeeting(meetingId)
              }
              setMeeting(updatedMeeting)
              dispatch(updatedMeeting.status === 'STARTED' ? { type: 'playback.finished' } : { type: 'event', event: liveEvent })
            })().catch(() => dispatch({ type: 'playback.finished' }))
            return
          }
          dispatch({ type: 'event', event: liveEvent })
        } catch {
          dispatch({ type: 'event', event: { type: 'stream.warning', meetingId, message: '해석할 수 없는 이벤트를 건너뛰었습니다.' } })
        }
      }
      socket.onerror = () => socket.close()
      socket.onclose = () => {
        if (!active || intentionallyClosedRef.current) return
        dispatch({ type: 'socket.closed' })
        retryRef.current = window.setTimeout(async () => {
          try { await loadTranscript() } catch { /* 다음 연결에서 다시 동기화한다. */ }
          connect(true)
        }, 1500)
      }
    }

    Promise.all([api.getMeeting(meetingId), loadTranscript(), api.getAiConfiguration()]).then(([loadedMeeting, , configuration]) => {
      if (!active) return
      setMeeting(loadedMeeting)
      meetingStartedAtRef.current = loadedMeeting.startedAt ?? null
      setAiConfiguration(configuration)
      if (loadedMeeting.status === 'STARTED' && loadedMeeting.inputSource === 'MOCK') connect()
      else if (loadedMeeting.status === 'STOPPED' || loadedMeeting.status === 'COMPLETED') {
        dispatch({ type: 'event', event: { type: 'stream.stopped', meetingId } })
      }
    }).catch((caught) => {
      if (caught instanceof ApiError && caught.status === 401) onUnauthorized()
      else setError(caught instanceof Error ? caught.message : '회의를 불러오지 못했습니다.')
    }).finally(() => active && setLoading(false))

    return () => {
      active = false
      intentionallyClosedRef.current = true
      window.clearTimeout(retryRef.current)
      socketRef.current?.close()
      void stopLiveAi()
    }
  }, [loadTranscript, meetingId, onUnauthorized, stopLiveAi])

  async function start() {
    setError('')
    try {
      const updated = await api.startMeeting(meetingId)
      setMeeting(updated)
      window.location.reload()
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) onUnauthorized()
      else setError(caught instanceof Error ? caught.message : '가상 스트림을 시작하지 못했습니다.')
    }
  }

  async function stop() {
    setStopping(true)
    setError('')
    intentionallyClosedRef.current = true
    window.clearTimeout(retryRef.current)
    try {
      const updated = await api.stopMeeting(meetingId)
      setMeeting(updated)
      socketRef.current?.close()
      dispatch({ type: 'event', event: { type: 'stream.stopped', meetingId } })
      try {
        await loadTranscript()
      } catch {
        setError('회의는 종료됐지만 저장된 전사를 다시 불러오지 못했습니다. 새로고침해 주세요.')
      }
    } catch (caught) {
      intentionallyClosedRef.current = false
      if (caught instanceof ApiError && caught.status === 401) onUnauthorized()
      else setError(caught instanceof Error ? caught.message : '회의를 종료하지 못했습니다.')
    } finally {
      setStopping(false)
    }
  }

  if (loading) return <main className="grid min-h-[70vh] place-items-center"><LoaderCircle aria-label="회의 불러오는 중" className="h-8 w-8 animate-spin text-moss" /></main>
  if (!meeting) return <main className="mx-auto max-w-xl px-5 py-20"><p role="alert" className="rounded-2xl bg-red-50 p-5 font-semibold text-red-800">{error || '회의를 찾을 수 없습니다.'}</p></main>

  const [connectionLabel, connectionClass] = connectionCopy[state.connection]
  const partials = Object.values(state.partials).sort((a, b) => a.startMs - b.startMs)
  const isLive = meeting.status === 'STARTED'
  const localRecording = meeting.inputSource === 'LOCAL_RECORDING'
  const liveAi = meeting.inputSource === 'LIVE'
  const microphoneMode = localRecording || liveAi

  return (
    <main className="mx-auto max-w-7xl px-5 py-8 md:px-10 md:py-10">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <AppLink href="/meetings" className="inline-flex items-center gap-2 text-sm font-bold text-moss hover:underline"><ArrowLeft className="h-4 w-4" />회의 목록</AppLink>
        <span aria-live="polite" className={`inline-flex items-center gap-2 rounded-full px-3 py-1.5 text-xs font-black ${connectionClass}`}>
          {state.connection === 'connected' ? <Wifi className="h-3.5 w-3.5" /> : state.connection === 'error' ? <WifiOff className="h-3.5 w-3.5" /> : <RefreshCw className={`h-3.5 w-3.5 ${['connecting', 'reconnecting'].includes(state.connection) ? 'animate-spin' : ''}`} />}
          {localRecording && isLive ? '녹음 준비 · 음성 인식 미연결' : liveAi && state.connection === 'idle' ? '마이크 시작 대기' : liveAi && state.connection === 'connected' ? '실시간 AI 연결됨' : connectionLabel}
        </span>
      </div>

      <section className="mt-7 grid gap-6 lg:grid-cols-[1fr_19rem]">
        <div>
          <div className="rounded-[2rem] bg-moss p-7 text-white shadow-soft md:p-9">
            <div className="flex flex-wrap items-start justify-between gap-5">
              <div><p className="text-xs font-black uppercase tracking-[0.2em] text-mint/70">{localRecording ? 'Microphone recording' : liveAi ? 'Live AI transcript' : 'Live transcript · Mock'}</p><h1 className="mt-3 max-w-3xl text-3xl font-black leading-tight tracking-[-0.045em] md:text-5xl">{meeting.title}</h1><p className="mt-4 flex items-center gap-2 text-sm text-white/65"><Radio className="h-4 w-4" />{localRecording ? '녹음은 보존하며 음성 인식은 연결하지 않습니다.' : liveAi ? '원본 녹음과 실시간 AI 전사를 같은 마이크에서 독립 처리합니다.' : '가상 발언 이벤트를 시간순으로 표시합니다.'}</p></div>
              {isLive && !microphoneMode ? <button className="button-stop" onClick={stop} disabled={stopping}>{stopping ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <CircleStop className="h-4 w-4" />}회의 종료</button> : meeting.status === 'CREATED' ? <button className="button-light" onClick={start}><Radio className="h-4 w-4" />{microphoneMode ? '회의 준비' : '가상 스트림 시작'}</button> : null}
            </div>
          </div>

          {error && <p role="alert" className="mt-4 rounded-2xl bg-red-50 p-4 text-sm font-semibold text-red-800">{error}</p>}
          <MeetingTools meeting={meeting} onChanged={setMeeting} onUnauthorized={onUnauthorized} />
          {state.notice && <p role="status" className="mt-4 flex gap-2 rounded-2xl bg-amber-50 p-4 text-sm font-semibold text-amber-900"><AlertTriangle className="h-5 w-5 shrink-0" />{state.notice}</p>}
          {audioGaps.length > 0 && <div className="mt-4 rounded-2xl bg-amber-50 p-4 text-sm text-amber-900" aria-label="전사 누락 구간">
            <p className="font-bold">AI 전사 누락 구간 · 원본 녹음은 별도로 보존됩니다</p>
            <ul>{audioGaps.map((gap, index) => <li key={index}>{(gap.startMs / 1000).toFixed(1)}~{(gap.endMs / 1000).toFixed(1)}초</li>)}</ul>
          </div>}
          {microphoneMode && <RecordingPanel meetingId={meetingId} isLive={isLive} aiEnabled={liveAi} aiConnection={state.connection} onStreamStarted={liveAi ? startLiveAi : undefined} onStreamStopping={liveAi ? stopLiveAi : undefined} onFinished={stop} />}

          <div className="mt-7 space-y-3" aria-live="polite" aria-label="실시간 회의록">
            {state.segments.map((segment) => <TranscriptCard key={segment.segmentId} segment={segment} onSave={async (text, revision) => { await api.correctSegment(meetingId, { ...segment, revision }, text); await loadTranscript() }} />)}
            {partials.map((segment) => <TranscriptCard key={`partial-${segment.eventId}`} segment={segment} partial />)}
            {!localRecording && state.segments.length === 0 && partials.length === 0 && (
              <div className="rounded-[2rem] border border-dashed border-moss/20 bg-white/45 py-16 text-center"><Radio className="mx-auto h-9 w-9 animate-pulse text-moss/30" /><p className="mt-4 font-black">{liveAi ? '마이크를 시작하면 실시간 자막이 표시됩니다' : '첫 가상 발언을 기다리고 있어요'}</p><p className="mt-1 text-sm text-slate-500">연결되면 임시 자막부터 차례로 표시됩니다.</p></div>
            )}
          </div>
          <SummaryPanel meetingId={meetingId} stopped={!isLive && meeting.status !== 'CREATED'} transcriptKey={state.segments.map((segment) => `${segment.segmentId}:${segment.revision}`).join('|')} hasTopic={Boolean(meeting.topicId)} aiConfiguration={aiConfiguration} onChanged={loadTranscript} />
        </div>

        <aside className="space-y-4 lg:sticky lg:top-6 lg:self-start">
          {!microphoneMode && <MockNotice />}
          {liveAi && <div className="rounded-2xl border border-coral/30 bg-red-50 p-4 text-sm leading-6 text-red-900"><strong>유료 AI 사용</strong><br />마이크 시작 후 음성이 외부 STT로 전송됩니다. 최대 {Math.round((aiConfiguration?.maximumAudioSeconds ?? 0) / 60)}분 · 회의당 최대 ${aiConfiguration?.maximumMeetingUsd?.toFixed(2) ?? '0.00'}로 서버에서 제한합니다.</div>}
          <p className="rounded-2xl border border-moss/10 bg-white/70 p-4 text-xs font-semibold leading-5 text-slate-500">{localRecording ? '외부 AI 전송과 과금은 없습니다. 페이지를 닫으면 마이크가 중지됩니다. 브라우저에 저장된 청크는 이 회의 화면에서 복구하세요.' : liveAi ? '실시간 전사가 끊겨도 원본 녹음은 계속됩니다. 누락 구간은 화면에 시간 범위로 표시되며 이미 전송한 음성을 재전송하지 않습니다.' : '이 데모에서는 라이브 화면을 벗어나면 가상 재생이 끝납니다. 이미 확정되어 저장된 발언은 유지됩니다.'}</p>
          <div className="rounded-2xl border border-white bg-white p-5 shadow-sm"><p className="eyebrow">상태 읽는 법</p><dl className="mt-4 space-y-4 text-sm"><div><dt className="font-black text-ink">전사 상태</dt><dd className="mt-1 leading-5 text-slate-500">임시·확정·교정 여부를 표시합니다.</dd></div><div><dt className="font-black text-ink">화자 상태</dt><dd className="mt-1 leading-5 text-slate-500">전사와 별도로 늦게 갱신될 수 있습니다.</dd></div><div><dt className="font-black text-ink">원문</dt><dd className="mt-1 leading-5 text-slate-500">교정되어도 최초 확정 문장을 보존합니다.</dd></div></dl></div>
        </aside>
      </section>
    </main>
  )
}
