/* global AudioWorkletProcessor, registerProcessor, sampleRate */

class StreamingPcm16Resampler {
  previous
  position = 0

  constructor(inputRate, outputRate = 16_000) {
    this.inputRate = inputRate
    this.outputRate = outputRate
  }

  push(input) {
    if (input.length === 0) return new Int16Array()
    const samples = this.previous === undefined ? input : Float32Array.from([this.previous, ...input])
    const step = this.inputRate / this.outputRate
    const output = []
    while (this.position < samples.length - 1) {
      const left = Math.floor(this.position)
      const fraction = this.position - left
      const sample = samples[left] + (samples[left + 1] - samples[left]) * fraction
      const clipped = Math.max(-1, Math.min(1, sample))
      output.push(Math.round(clipped < 0 ? clipped * 0x8000 : clipped * 0x7fff))
      this.position += step
    }
    this.position -= samples.length - 1
    this.previous = samples[samples.length - 1]
    return Int16Array.from(output)
  }
}

class PcmCaptureProcessor extends AudioWorkletProcessor {
  resampler = new StreamingPcm16Resampler(sampleRate)
  frame = new Int16Array(1_600)
  length = 0

  constructor() {
    super()
    this.port.onmessage = (event) => {
      if (event.data?.type === 'flush') {
        this.emit(true)
        this.port.postMessage({ type: 'flush.complete' })
      }
    }
  }

  process(inputs) {
    const channels = inputs[0]
    if (!channels?.length || !channels[0]?.length) return true
    const mono = new Float32Array(channels[0].length)
    for (const channel of channels) {
      for (let index = 0; index < mono.length; index += 1) mono[index] += channel[index] / channels.length
    }
    const pcm = this.resampler.push(mono)
    let offset = 0
    while (offset < pcm.length) {
      const copied = Math.min(this.frame.length - this.length, pcm.length - offset)
      this.frame.set(pcm.subarray(offset, offset + copied), this.length)
      this.length += copied
      offset += copied
      if (this.length === this.frame.length) this.emit(false)
    }
    return true
  }

  emit(partial) {
    if (this.length === 0 || (!partial && this.length < this.frame.length)) return
    const pcm = this.frame.slice(0, this.length)
    this.length = 0
    this.port.postMessage({ type: 'pcm', pcm: pcm.buffer }, [pcm.buffer])
  }
}

registerProcessor('pcm-capture-processor', PcmCaptureProcessor)
