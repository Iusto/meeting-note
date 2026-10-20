import type { LiveEvent, TranscriptSegment } from './types'

export type ConnectionState = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'stopped' | 'error'

export interface LiveState {
  connection: ConnectionState
  streamSessionId?: string
  segments: TranscriptSegment[]
  partials: Record<string, TranscriptSegment>
  lastSequenceByEvent: Record<string, number>
  seenFinalEvents: Record<string, true>
  notice?: string
}

export type LiveAction =
  | { type: 'hydrate'; segments: TranscriptSegment[] }
  | { type: 'connecting'; reconnecting?: boolean }
  | { type: 'socket.closed' }
  | { type: 'playback.finished' }
  | { type: 'event'; event: LiveEvent }

export const initialLiveState: LiveState = {
  connection: 'idle',
  segments: [],
  partials: {},
  lastSequenceByEvent: {},
  seenFinalEvents: {},
}

const keyFor = (event: LiveEvent) => event.eventId ?? event.segmentId ?? `sequence:${event.sequence ?? -1}`

function sorted(segments: TranscriptSegment[]) {
  return [...segments].sort((left, right) => left.startMs - right.startMs || left.sequence - right.sequence)
}

function eventToSegment(event: LiveEvent, fallbackStatus: TranscriptSegment['transcriptStatus']): TranscriptSegment {
  const originalText = event.originalText ?? event.text ?? ''
  return {
    segmentId: event.segmentId ?? event.eventId ?? `pending-${event.sequence ?? 0}`,
    eventId: event.eventId ?? event.segmentId ?? `pending-${event.sequence ?? 0}`,
    sequence: event.sequence ?? 0,
    startMs: event.startMs ?? 0,
    endMs: event.endMs ?? event.startMs ?? 0,
    originalText,
    displayText: event.displayText ?? event.text ?? originalText,
    transcriptStatus: event.transcriptStatus ?? fallbackStatus,
    speakerLabel: event.speakerLabel,
    speakerStatus: event.speakerStatus ?? 'UNKNOWN',
    revision: event.revision ?? 0,
    streamSessionId: event.streamSessionId,
  }
}

function patchSegment(
  state: LiveState,
  segmentId: string | undefined,
  patch: (segment: TranscriptSegment) => TranscriptSegment,
) {
  if (!segmentId) return state
  const index = state.segments.findIndex(
    (segment) => segment.segmentId === segmentId || segment.eventId === segmentId,
  )
  if (index < 0) return state
  const segments = [...state.segments]
  segments[index] = patch(segments[index])
  return { ...state, segments }
}

export function liveReducer(state: LiveState, action: LiveAction): LiveState {
  if (action.type === 'hydrate') {
    const seenFinalEvents = { ...state.seenFinalEvents }
    const partials = { ...state.partials }
    action.segments.forEach((segment) => {
      seenFinalEvents[segment.eventId] = true
      delete partials[segment.eventId]
      delete partials[segment.segmentId]
    })
    return { ...state, segments: sorted(action.segments), partials, seenFinalEvents }
  }
  if (action.type === 'connecting') {
    return { ...state, connection: action.reconnecting ? 'reconnecting' : 'connecting', notice: undefined }
  }
  if (action.type === 'socket.closed') {
    return state.connection === 'stopped' ? state : { ...state, connection: 'reconnecting' }
  }
  if (action.type === 'playback.finished') {
    return { ...state, connection: 'idle', notice: '가상 이벤트 재생이 끝났습니다. 저장된 전사를 동기화했습니다.' }
  }

  const event = action.event
  if (event.type === 'session.ready') {
    return { ...state, connection: 'connected', streamSessionId: event.streamSessionId, notice: undefined }
  }
  if (event.type === 'stream.stopped') return { ...state, connection: 'stopped' }
  if (event.type === 'stream.warning') return { ...state, notice: event.message ?? '스트림 상태를 확인해 주세요.' }
  if (event.type === 'stream.error') {
    return { ...state, connection: 'error', notice: event.message ?? '실시간 전사 연결에 문제가 생겼습니다.' }
  }

  const eventKey = keyFor(event)
  const sequence = event.sequence ?? 0
  const lastSequence = state.lastSequenceByEvent[eventKey] ?? -1

  if (event.type === 'transcript.partial') {
    if (sequence <= lastSequence || state.seenFinalEvents[eventKey]) return state
    return {
      ...state,
      partials: { ...state.partials, [eventKey]: eventToSegment(event, 'PARTIAL') },
      lastSequenceByEvent: { ...state.lastSequenceByEvent, [eventKey]: sequence },
    }
  }

  if (event.type === 'transcript.final') {
    if (state.seenFinalEvents[eventKey]) return state
    const partials = { ...state.partials }
    delete partials[eventKey]
    return {
      ...state,
      segments: sorted([...state.segments, eventToSegment(event, 'FINAL')]),
      partials,
      lastSequenceByEvent: { ...state.lastSequenceByEvent, [eventKey]: Math.max(lastSequence, sequence) },
      seenFinalEvents: { ...state.seenFinalEvents, [eventKey]: true },
    }
  }

  if (event.type === 'speaker.updated') {
    return patchSegment(state, event.segmentId ?? event.eventId, (segment) => {
      if ((event.revision ?? 0) < segment.revision) return segment
      return {
        ...segment,
        speakerLabel: event.speakerLabel,
        speakerStatus: event.speakerStatus ?? segment.speakerStatus,
        revision: Math.max(segment.revision, event.revision ?? segment.revision),
      }
    })
  }

  if (event.type === 'transcript.corrected') {
    return patchSegment(state, event.segmentId ?? event.eventId, (segment) => {
      const revision = event.revision ?? 0
      if (segment.userLocked || revision <= segment.revision) return segment
      return {
        ...segment,
        originalText: segment.originalText,
        displayText: event.displayText ?? event.text ?? segment.displayText,
        transcriptStatus: event.transcriptStatus ?? 'CORRECTED',
        revision,
      }
    })
  }

  return state
}
