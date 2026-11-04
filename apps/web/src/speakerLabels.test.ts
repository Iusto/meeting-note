import { describe, expect, it } from 'vitest'
import { createSpeakerLabels } from './speakerLabels'

describe('speaker display labels', () => {
  it('keeps session-specific speakers distinct and repeated speakers consistent', () => {
    const first = 'speaker_51bfcc3e-874c-4e1f-bfd7-f50fb26cdc2b_1'
    const second = 'speaker_61bfcc3e-874c-4e1f-bfd7-f50fb26cdc2b_1'
    const display = createSpeakerLabels([first, second, first, 'speaker_0'].map(speakerLabel => ({ speakerLabel })))
    expect(display(first)).toBe('화자 1')
    expect(display(second)).toBe('화자 2')
    expect(display('speaker_0')).toBe('화자 3')
  })

  it('preserves names and handles unknown speakers', () => {
    const display = createSpeakerLabels([{ speakerLabel: '김민수' }])
    expect(display('김민수')).toBe('김민수')
    expect(display(null)).toBe('화자 미정')
    expect(display('')).toBe('화자 미정')
  })
})
