import { describe, expect, it } from 'vitest'
import { initialLiveState, liveReducer } from './liveReducer'
import type { LiveEvent, TranscriptSegment } from './types'

const event = (overrides: Partial<LiveEvent> = {}): LiveEvent => ({
  type: 'transcript.partial',
  meetingId: 'meeting-1',
  streamSessionId: 'stream-1',
  eventId: 'event-1',
  sequence: 1,
  startMs: 1000,
  endMs: 2000,
  text: '임시 발언',
  speakerStatus: 'UNKNOWN',
  ...overrides,
})

describe('liveReducer', () => {
  it('ignores stale partials and replaces only with a newer sequence', () => {
    const first = liveReducer(initialLiveState, { type: 'event', event: event({ sequence: 2, text: '새 임시 발언' }) })
    const stale = liveReducer(first, { type: 'event', event: event({ sequence: 1, text: '오래된 발언' }) })
    expect(stale).toBe(first)
    expect(stale.partials['event-1'].displayText).toBe('새 임시 발언')
  })

  it('stores a final once and removes its partial', () => {
    const partial = liveReducer(initialLiveState, { type: 'event', event: event() })
    const finalEvent = event({ type: 'transcript.final', sequence: 2, segmentId: 'segment-1', originalText: '확정 원문', displayText: '확정 원문' })
    const finalized = liveReducer(partial, { type: 'event', event: finalEvent })
    const duplicate = liveReducer(finalized, { type: 'event', event: finalEvent })
    expect(finalized.partials).toEqual({})
    expect(duplicate.segments).toHaveLength(1)
    expect(duplicate).toBe(finalized)
  })

  it('orders out-of-order final events by timestamp', () => {
    const later = liveReducer(initialLiveState, { type: 'event', event: event({ type: 'transcript.final', eventId: 'later', segmentId: 'later', sequence: 9, startMs: 9000 }) })
    const result = liveReducer(later, { type: 'event', event: event({ type: 'transcript.final', eventId: 'earlier', segmentId: 'earlier', sequence: 3, startMs: 3000 }) })
    expect(result.segments.map((segment) => segment.segmentId)).toEqual(['earlier', 'later'])
  })

  it('applies late speaker updates independently from transcript status', () => {
    const final = liveReducer(initialLiveState, { type: 'event', event: event({ type: 'transcript.final', segmentId: 'segment-1', originalText: '원문' }) })
    const result = liveReducer(final, { type: 'event', event: event({ type: 'speaker.updated', segmentId: 'segment-1', revision: 1, speakerLabel: 'speaker_2', speakerStatus: 'CONFIRMED' }) })
    expect(result.segments[0]).toMatchObject({ transcriptStatus: 'FINAL', speakerLabel: 'speaker_2', speakerStatus: 'CONFIRMED' })
  })

  it('preserves original text when a newer correction arrives and rejects stale corrections', () => {
    const segment: TranscriptSegment = { segmentId: 'segment-1', eventId: 'event-1', sequence: 1, startMs: 0, endMs: 1000, originalText: '로떠 번호', displayText: '로떠 번호', transcriptStatus: 'FINAL', speakerStatus: 'UNKNOWN', revision: 0 }
    const hydrated = liveReducer(initialLiveState, { type: 'hydrate', segments: [segment] })
    const corrected = liveReducer(hydrated, { type: 'event', event: event({ type: 'transcript.corrected', segmentId: 'segment-1', revision: 2, displayText: 'LOT 번호' }) })
    const stale = liveReducer(corrected, { type: 'event', event: event({ type: 'transcript.corrected', segmentId: 'segment-1', revision: 1, displayText: '오래된 교정' }) })
    expect(stale.segments[0]).toMatchObject({ originalText: '로떠 번호', displayText: 'LOT 번호', transcriptStatus: 'CORRECTED', revision: 2 })
  })

  it('never overwrites a user-locked segment with an automatic correction', () => {
    const locked: TranscriptSegment = { segmentId: 'segment-1', eventId: 'event-1', sequence: 1, startMs: 0, endMs: 1000, originalText: '원문', displayText: '사용자 수정', transcriptStatus: 'CORRECTED', speakerStatus: 'CONFIRMED', revision: 3, userLocked: true }
    const hydrated = liveReducer(initialLiveState, { type: 'hydrate', segments: [locked] })
    const result = liveReducer(hydrated, { type: 'event', event: event({ type: 'transcript.corrected', segmentId: 'segment-1', revision: 4, displayText: '자동 교정' }) })
    expect(result.segments[0].displayText).toBe('사용자 수정')
  })

  it('removes a stale partial when the durable transcript contains its final event', () => {
    const partial = liveReducer(initialLiveState, { type: 'event', event: event({ eventId: 'event-1' }) })
    const final: TranscriptSegment = { segmentId: 'segment-1', eventId: 'event-1', sequence: 2, startMs: 1000, endMs: 2000, originalText: '확정 원문', displayText: '확정 원문', transcriptStatus: 'FINAL', speakerStatus: 'UNKNOWN', revision: 0 }
    const hydrated = liveReducer(partial, { type: 'hydrate', segments: [final] })
    expect(hydrated.partials).toEqual({})
    expect(hydrated.segments).toEqual([final])
  })
})
