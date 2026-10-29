export type MeetingStatus = 'CREATED' | 'STARTED' | 'STOPPED' | 'COMPLETED' | string

export interface Meeting {
  id: string
  title: string
  revision: number
  status: MeetingStatus
  inputSource?: 'MOCK' | 'LOCAL_RECORDING' | 'LIVE'
  topicId?: string | null
  createdAt?: string
  startedAt?: string | null
  endedAt?: string | null
}

export interface AiConfiguration {
  enabled: boolean
  speechAvailable: boolean
  llmAvailable: boolean
  maximumAudioSeconds: number
  maximumMeetingUsd: number
}

export type TranscriptStatus =
  | 'PARTIAL'
  | 'FINAL'
  | 'CORRECTION_PENDING'
  | 'CORRECTED'
  | 'REVIEW_REQUIRED'

export type SpeakerStatus = 'UNKNOWN' | 'TENTATIVE' | 'CONFIRMED' | 'OVERLAP'

export interface TranscriptSegment {
  segmentId: string
  eventId: string
  sequence: number
  startMs: number
  endMs: number
  originalText: string
  displayText: string
  transcriptStatus: TranscriptStatus
  speakerLabel?: string | null
  speakerStatus: SpeakerStatus
  revision: number
  userLocked?: boolean
  streamSessionId?: string
}

export type LiveEventType =
  | 'session.ready'
  | 'transcript.partial'
  | 'transcript.final'
  | 'speaker.updated'
  | 'transcript.corrected'
  | 'stream.stopped'
  | 'stream.warning'
  | 'stream.error'

export interface LiveEvent {
  type: LiveEventType
  meetingId: string
  streamSessionId?: string
  eventId?: string
  sequence?: number
  startMs?: number
  endMs?: number
  text?: string
  speakerLabel?: string | null
  speakerStatus?: SpeakerStatus
  segmentId?: string
  revision?: number
  originalText?: string
  displayText?: string
  transcriptStatus?: TranscriptStatus
  message?: string
}
