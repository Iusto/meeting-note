const PCM16_SCALE = 0x7fff

export class StreamingPcm16Resampler {
  private previous: number | undefined
  private position = 0

  constructor(
    private readonly inputRate: number,
    private readonly outputRate = 16_000,
  ) {
    if (!Number.isFinite(inputRate) || inputRate <= 0 || outputRate <= 0 || outputRate > inputRate) {
      throw new Error('Unsupported audio sample rate')
    }
  }

  push(input: Float32Array) {
    if (input.length === 0) return new Int16Array()
    const samples = this.previous === undefined ? input : Float32Array.from([this.previous, ...input])
    const step = this.inputRate / this.outputRate
    const output: number[] = []
    while (this.position < samples.length - 1) {
      const left = Math.floor(this.position)
      const fraction = this.position - left
      const sample = samples[left] + (samples[left + 1] - samples[left]) * fraction
      output.push(toPcm16(sample))
      this.position += step
    }
    this.position -= samples.length - 1
    this.previous = samples[samples.length - 1]
    return Int16Array.from(output)
  }
}

export function toPcm16(sample: number) {
  const clipped = Math.max(-1, Math.min(1, sample))
  return Math.round(clipped < 0 ? clipped * 0x8000 : clipped * PCM16_SCALE)
}
