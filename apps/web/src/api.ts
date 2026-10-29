import type { AiConfiguration, Meeting, TranscriptSegment } from './types'
import type { RecordingChunk } from './recordingStore'

export interface SavedRecording {
  state: string
  bytes: number | null
  sha256: string | null
  durationMs: number | null
  mimeType: string
  chunkCount: number
}

const API_PREFIX = '/api/v1'

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code?: string,
  ) {
    super(message)
  }
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_PREFIX}${path}`, {
    ...init,
    credentials: 'include',
    headers: {
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
  })

  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as { message?: string; code?: string } | null
    throw new ApiError(payload?.message ?? '요청을 처리하지 못했습니다.', response.status, payload?.code)
  }

  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const api = {
  getLoginConfiguration: () => request<{ setupRequired: boolean }>('/session/configuration'),
  setupOwner: (credentials: { token: string; username: string; password: string; rememberMe: boolean }) =>
    request<void>('/session/setup', { method: 'POST', body: JSON.stringify(credentials) }),
  login: (credentials: { username: string; password: string; rememberMe: boolean }) =>
    request<void>('/session', { method: 'POST', body: JSON.stringify(credentials) }),
  getSession: () => request<unknown>('/session'),
  createSession: (token: string) =>
    request<void>('/session', { method: 'POST', body: JSON.stringify({ token }) }),
  deleteSession: () => request<void>('/session', { method: 'DELETE' }),
  listMeetings: (query = '') => request<Meeting[]>(`/meetings?${new URLSearchParams({ q: query })}`),
  updateMeeting: (meetingId: string, title: string, revision: number) =>
    request<Meeting>(`/meetings/${meetingId}`, { method: 'PATCH', body: JSON.stringify({ title, revision }) }),
  deleteMeeting: (meetingId: string, revision: number) => request<{ deleted: true; cleanupPending: boolean }>(`/meetings/${meetingId}?revision=${revision}`, { method: 'DELETE' }),
  getAiConfiguration: () => request<AiConfiguration>('/ai/configuration'),
  createMeeting: (title: string, inputSource: 'MOCK' | 'LOCAL_RECORDING' | 'LIVE' = 'MOCK', topicId?: string) =>
    request<Meeting>('/meetings', { method: 'POST', body: JSON.stringify({ title, inputSource, topicId: topicId || null }) }),
  getMeeting: (meetingId: string) => request<Meeting>(`/meetings/${meetingId}`),
  startMeeting: (meetingId: string) =>
    request<Meeting>(`/meetings/${meetingId}/start`, { method: 'POST' }),
  stopMeeting: (meetingId: string) =>
    request<Meeting>(`/meetings/${meetingId}/stop`, { method: 'POST' }),
  getTranscript: (meetingId: string) =>
    request<TranscriptSegment[]>(`/meetings/${meetingId}/transcript`),
  correctSegment: (meetingId: string, segment: TranscriptSegment, displayText: string) =>
    request<TranscriptSegment>(`/meetings/${meetingId}/segments/${segment.segmentId}`, { method: 'PATCH', body: JSON.stringify({ displayText, revision: segment.revision, reason: '사용자 검토' }) }),
  getRecording: (meetingId: string) => request<SavedRecording>(`/meetings/${meetingId}/recording`),
  uploadChunk: (chunk: RecordingChunk, mimeType: string) => {
    const query = new URLSearchParams({ sequence: String(chunk.sequence), startMs: String(chunk.startMs), endMs: String(chunk.endMs), sha256: chunk.sha256, mimeType })
    return request<unknown>(`/meetings/${chunk.meetingId}/recording-chunks?${query}`, {
      method: 'POST', body: chunk.blob, headers: { 'Content-Type': 'application/octet-stream' }, signal: AbortSignal.timeout(15_000),
    })
  },
  completeRecording: (meetingId: string, chunkCount: number) =>
    request<SavedRecording>(`/meetings/${meetingId}/recording-complete`, { method: 'POST', body: JSON.stringify({ chunkCount }), signal: AbortSignal.timeout(30_000) }),
}

export function liveSocketUrl(meetingId: string, audioOffsetMs?: number) {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const query = audioOffsetMs === undefined ? '' : `?${new URLSearchParams({ audioOffsetMs: String(audioOffsetMs) })}`
  return `${protocol}//${window.location.host}${API_PREFIX}/meetings/${meetingId}/live${query}`
}
