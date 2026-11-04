export function createSpeakerLabels(segments: ReadonlyArray<{ speakerLabel?: string | null }>) {
  const labels = new Map<string, string>()
  for (const { speakerLabel } of segments) {
    if (speakerLabel && !labels.has(speakerLabel) && /^speaker_(?:[0-9a-f-]{36}_)?\d+$/i.test(speakerLabel)) {
      labels.set(speakerLabel, `화자 ${labels.size + 1}`)
    }
  }
  return (label?: string | null) => label?.trim() ? labels.get(label) ?? label : '화자 미정'
}
