import type { TranscriptSegment } from './types'

export function findTranscriptMatches(segments: TranscriptSegment[], query: string, speakerName: (label?: string | null) => string) {
  const needle = query.trim().toLocaleLowerCase()
  if (!needle) return []
  return segments.filter(segment => [segment.originalText, segment.displayText, speakerName(segment.speakerLabel)]
    .some(value => value.toLocaleLowerCase().includes(needle)))
}
