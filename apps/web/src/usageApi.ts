import { request } from './api'

export interface Settings {
  monthlyBudgetUsd: number
  warnAtPercent: number
  perMeetingMaxMinutes: number
  recordingRetentionDays: number | null
  revision: number
  paidApiEnabled: false
}

export interface Usage {
  month: string
  currency: 'USD'
  paidApiEnabled: false
  recordedSeconds: number
  totalEstimatedUsd: number
  totalActualUsd: number | null
  reservedUsd: number
  budgetCommittedUsd: number
  budgetRemainingUsd: number
  unreconciledCount: number
  budgetUsd: number
  warnAtPercent: number
  budgetState: 'DISABLED' | 'OK' | 'WARNING' | 'EXHAUSTED'
  meetings: { meetingId: string; title: string; inputSource: string; recordedSeconds: number; recordingBytes: number; estimatedUsd: number; actualUsd: number | null; reservedUsd: number; budgetCommittedUsd: number; retentionReviewDue: boolean }[]
}

export const usageApi = {
  settings: () => request<Settings>('/settings'),
  saveSettings: (settings: Omit<Settings, 'paidApiEnabled'>) => request<Settings>('/settings', { method: 'PATCH', body: JSON.stringify(settings) }),
  usage: (month: string) => request<Usage>(`/usage?${new URLSearchParams({ month })}`),
}
