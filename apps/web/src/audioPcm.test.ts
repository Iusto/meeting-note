import { describe, expect, it } from 'vitest'
import { StreamingPcm16Resampler, toPcm16 } from './audioPcm'

describe('StreamingPcm16Resampler', () => {
  it('resamples one second of 48 kHz audio to exactly 16 kHz across uneven chunks', () => {
    const source = Float32Array.from({ length: 48_000 }, (_, index) => Math.sin(2 * Math.PI * 440 * index / 48_000) * 0.5)
    const resampler = new StreamingPcm16Resampler(48_000)
    const chunks = [source.subarray(0, 127), source.subarray(127, 10_003), source.subarray(10_003, 35_777), source.subarray(35_777)]
    const result = Int16Array.from(chunks.flatMap((chunk) => [...resampler.push(chunk)]))

    expect(result).toHaveLength(16_000)
    expect(result[0]).toBe(0)
    expect(result[4_000]).toBeCloseTo(toPcm16(source[12_000]), -1)
    expect(result[15_999]).toBeCloseTo(toPcm16(source[47_997]), -1)
  })

  it('clips samples to signed little-endian PCM16 range values', () => {
    expect([toPcm16(-2), toPcm16(-1), toPcm16(0), toPcm16(1), toPcm16(2)])
      .toEqual([-32768, -32768, 0, 32767, 32767])
  })
})
