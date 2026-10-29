import { request } from './api'

export interface Topic { id: string; name: string; description: string | null }
export interface Term {
  id: string; canonical: string; meaning: string; aliases: string[]
  status: 'PENDING' | 'APPROVED' | 'REJECTED'; source: 'MANUAL' | 'MOCK' | 'OPENAI'
}
export type TermInput = Pick<Term, 'canonical' | 'meaning' | 'aliases' | 'status'>
export interface Evidence { text: string; sourceSegmentIds: string[] }
export interface MeetingSummary {
  id: string; meetingId: string; version: number; provider: 'MOCK' | 'OPENAI'; stale: boolean
  content: {
    summary: string; sourceSegmentIds: string[]; decisions: Evidence[]
    actionItems: (Evidence & { assignee: string | null; dueDate: string | null })[]
    unresolved: { text: string; sourceSegmentIds: string[] }[]
  }
}
export interface CorrectionSuggestion {
  id: string; segmentId: string; expectedRevision: number; originalSpan: string
  replacement: string; reason: string; state: 'PENDING' | 'APPLIED' | 'REJECTED'
}
const body = (method: string, value: unknown) => ({ method, body: JSON.stringify(value) })
export const knowledgeApi = {
  topics: () => request<Topic[]>('/topics'),
  createTopic: (name: string) => request<Topic>('/topics', body('POST', { name, description: '' })),
  editTopic: (id: string, name: string, description: string) => request<Topic>(`/topics/${id}`, body('PATCH', { name, description })),
  deleteTopic: (id: string) => request<void>(`/topics/${id}`, { method: 'DELETE' }),
  terms: (id: string) => request<Term[]>(`/topics/${id}/terms`),
  createTerm: (id: string, term: TermInput) => request<Term>(`/topics/${id}/terms`, body('POST', term)),
  editTerm: (topicId: string, termId: string, term: TermInput) => request<Term>(`/topics/${topicId}/terms/${termId}`, body('PATCH', term)),
  deleteTerm: (topicId: string, termId: string) => request<void>(`/topics/${topicId}/terms/${termId}`, { method: 'DELETE' }),
  keyterms: (id: string) => request<string[]>(`/topics/${id}/keyterms`),
  suggestTerms: (id: string) => request<Term[]>(`/topics/${id}/suggest-terms`, { method: 'POST' }),
  summary: (id: string) => request<MeetingSummary>(`/meetings/${id}/summary`),
  generateSummary: (id: string) => request<MeetingSummary>(`/meetings/${id}/summary`, { method: 'POST' }),
  corrections: (id: string) => request<CorrectionSuggestion[]>(`/meetings/${id}/correction-suggestions`, { method: 'POST' }),
  reviewCorrection: (meetingId: string, id: string, state: 'APPLIED' | 'REJECTED') => request<CorrectionSuggestion>(`/meetings/${meetingId}/correction-suggestions/${id}`, body('PATCH', { state })),
}
