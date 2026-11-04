import { describe, expect, it } from 'vitest'
import { findTranscriptMatches } from './transcriptSearchMatches'
import type { TranscriptSegment } from './types'

describe('transcript search', () => {
  const segments = [{ segmentId: '1', originalText: 'Original API', displayText: '수정한 발언', speakerLabel: 'a' }, { segmentId: '2', originalText: 'Other', displayText: 'Other', speakerLabel: 'b' }] as TranscriptSegment[]
  const speaker = (label?: string | null) => label === 'a' ? '김정규' : '화자 2'
  it('matches original, edited text, and the displayed speaker without duplicating results', () => {
    for (const query of [' api ', '수정한', '김정규']) expect(findTranscriptMatches(segments, query, speaker).map(s => s.segmentId)).toEqual(['1'])
    expect(findTranscriptMatches(segments, '화자 2', speaker).map(s => s.segmentId)).toEqual(['2'])
  })
  it('handles empty and literal wildcard input', () => {
    expect(findTranscriptMatches(segments, ' ', speaker)).toEqual([])
    expect(findTranscriptMatches(segments, '%', speaker)).toEqual([])
  })
})
