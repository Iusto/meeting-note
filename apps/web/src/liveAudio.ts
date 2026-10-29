import type { LiveEvent } from './types'

const SAMPLE_RATE = 16_000
const SOCKET_OPEN = 1
const DEFAULT_MAX_BUFFERED_BYTES = 256 * 1024
const DEFAULT_MAX_QUEUED_SAMPLES = SAMPLE_RATE * 2

interface SocketLike {
  binaryType: BinaryType
  bufferedAmount: number
  readyState: number
  onopen: ((event: Event) => void) | null
  onmessage: ((event: MessageEvent) => void) | null
  onerror: ((event: Event) => void) | null
  onclose: ((event: CloseEvent) => void) | null
  send(data: string | ArrayBuffer): void
  close(): void
}

interface AudioFrame {
  data: ArrayBuffer
  startSample: number
  endSample: number
}

export interface DroppedAudioRange {
  startMs: number
  endMs: number
  reason: 'disconnected' | 'queue-full' | 'backpressure'
}

export interface LivePcmTransportOptions {
  meetingId: string
  baseOffsetMs?: number
  socketUrl: (meetingId: string, audioOffsetMs: number) => string
  onEvent: (event: LiveEvent) => void
  onDropped: (range: DroppedAudioRange) => void
  onConnecting?: (reconnecting: boolean) => void
  createSocket?: (url: string) => SocketLike
  schedule?: (callback: () => void, delayMs: number) => number
  cancelSchedule?: (id: number) => void
  maxBufferedBytes?: number
  maxQueuedSamples?: number
}

export class LivePcmTransport {
  private socket?: SocketLike
  private reconnectTimer?: number
  private queue: AudioFrame[] = []
  private capturedSamples = 0
  private reconnectAttempt = 0
  private ready = false
  private stopping = false
  private terminal = false
  private stopPromise?: Promise<void>
  private resolveStop?: () => void
  private generation = 0
  private readonly createSocket: (url: string) => SocketLike
  private readonly schedule: (callback: () => void, delayMs: number) => number
  private readonly cancelSchedule: (id: number) => void

  constructor(private readonly options: LivePcmTransportOptions) {
    this.createSocket = options.createSocket ?? ((url) => new WebSocket(url))
    this.schedule = options.schedule ?? ((callback, delayMs) => window.setTimeout(callback, delayMs))
    this.cancelSchedule = options.cancelSchedule ?? ((id) => window.clearTimeout(id))
  }

  start() {
    if (this.socket || this.terminal) return
    this.connect()
  }

  accept(pcm: Int16Array) {
    if (pcm.length === 0 || this.stopping) return
    const frame: AudioFrame = {
      data: pcm.buffer.slice(pcm.byteOffset, pcm.byteOffset + pcm.byteLength),
      startSample: this.capturedSamples,
      endSample: this.capturedSamples + pcm.length,
    }
    this.capturedSamples = frame.endSample
    if (this.terminal) { this.drop([frame], 'disconnected'); return }

    if (this.ready && this.socket?.readyState === SOCKET_OPEN) {
      if (this.socket.bufferedAmount <= (this.options.maxBufferedBytes ?? DEFAULT_MAX_BUFFERED_BYTES)) {
        this.socket.send(frame.data)
      } else {
        this.drop([frame], 'backpressure')
        this.restart()
      }
      return
    }

    if (this.socket) {
      const queuedSamples = this.queue.reduce((total, item) => total + item.endSample - item.startSample, 0)
      if (queuedSamples + pcm.length <= (this.options.maxQueuedSamples ?? DEFAULT_MAX_QUEUED_SAMPLES)) {
        this.queue.push(frame)
      } else {
        this.drop([...this.queue, frame], 'queue-full')
        this.queue = []
        this.restart()
      }
      return
    }

    this.drop([frame], 'disconnected')
  }

  stop() {
    if (this.stopPromise) return this.stopPromise
    this.stopping = true
    if (this.reconnectTimer !== undefined) this.cancelSchedule(this.reconnectTimer)
    this.reconnectTimer = undefined
    if (this.queue.length) {
      this.drop(this.queue, 'disconnected')
      this.queue = []
    }
    this.stopPromise = new Promise<void>((resolve) => {
      this.resolveStop = resolve
      if (this.ready && this.socket?.readyState === SOCKET_OPEN) {
        this.socket.send(JSON.stringify({ type: 'stream.stop' }))
        this.schedule(() => this.finish(), 10_000)
      } else {
        this.finish()
      }
    })
    return this.stopPromise
  }

  private connect() {
    if (this.stopping || this.terminal) return
    this.options.onConnecting?.(this.reconnectAttempt > 0)
    const generation = ++this.generation
    this.ready = false
    const firstQueuedSample = this.capturedSamples
    const socket = this.createSocket(this.options.socketUrl(this.options.meetingId, this.offsetMs(firstQueuedSample)))
    socket.binaryType = 'arraybuffer'
    this.socket = socket
    socket.onmessage = (message) => {
      if (generation !== this.generation || typeof message.data !== 'string') return
      try {
        const event = JSON.parse(message.data) as LiveEvent
        this.options.onEvent(event)
        if (event.type === 'session.ready') {
          this.ready = true
          this.reconnectAttempt = 0
          this.flushQueue()
        } else if (event.type === 'stream.error') {
          this.terminal = true
          this.finish()
        } else if (event.type === 'stream.stopped') {
          this.terminal = true
          this.finish()
        }
      } catch {
        this.options.onEvent({ type: 'stream.warning', meetingId: this.options.meetingId, message: '해석할 수 없는 실시간 음성 이벤트를 건너뛰었습니다.' })
      }
    }
    socket.onerror = () => socket.close()
    socket.onclose = () => {
      if (generation !== this.generation) return
      this.socket = undefined
      this.ready = false
      if (this.queue.length) {
        this.drop(this.queue, 'disconnected')
        this.queue = []
      }
      if (this.stopping || this.terminal) this.finish()
      else this.scheduleReconnect()
    }
  }

  private flushQueue() {
    const socket = this.socket
    if (!socket || socket.readyState !== SOCKET_OPEN) return
    const queued = this.queue
    this.queue = []
    for (let index = 0; index < queued.length; index += 1) {
      const frame = queued[index]
      if (socket.bufferedAmount > (this.options.maxBufferedBytes ?? DEFAULT_MAX_BUFFERED_BYTES)) {
        this.drop(queued.slice(index), 'backpressure')
        this.restart()
        return
      }
      socket.send(frame.data)
    }
  }

  private restart() {
    this.ready = false
    const socket = this.socket
    this.socket = undefined
    this.generation += 1
    socket?.close()
    this.scheduleReconnect()
  }

  private scheduleReconnect() {
    if (this.reconnectTimer !== undefined || this.stopping || this.terminal) return
    const delay = Math.min(8_000, 500 * (2 ** this.reconnectAttempt))
    this.reconnectAttempt += 1
    this.reconnectTimer = this.schedule(() => {
      this.reconnectTimer = undefined
      this.connect()
    }, delay)
  }

  private drop(frames: AudioFrame[], reason: DroppedAudioRange['reason']) {
    if (frames.length === 0) return
    this.options.onDropped({ startMs: this.offsetMs(frames[0].startSample), endMs: this.offsetMs(frames.at(-1)!.endSample), reason })
  }

  private offsetMs(samples: number) {
    return Math.max(0, this.options.baseOffsetMs ?? 0) + samplesToMs(samples)
  }

  private finish() {
    if (this.queue.length) { this.drop(this.queue, 'disconnected'); this.queue = [] }
    this.generation += 1
    if (this.reconnectTimer !== undefined) this.cancelSchedule(this.reconnectTimer)
    this.reconnectTimer = undefined
    this.socket?.close()
    this.socket = undefined
    this.ready = false
    this.resolveStop?.()
    this.resolveStop = undefined
  }
}

export class MicrophonePcmCapture {
  private context?: AudioContext
  private source?: MediaStreamAudioSourceNode
  private worklet?: AudioWorkletNode
  private flushPromise?: Promise<void>

  constructor(private readonly onFrame: (pcm: Int16Array) => void) {}

  async start(stream: MediaStream) {
    const context = new AudioContext()
    await context.audioWorklet.addModule(new URL('./pcmCaptureWorklet.js', import.meta.url))
    const source = context.createMediaStreamSource(stream)
    const worklet = new AudioWorkletNode(context, 'pcm-capture-processor', { numberOfInputs: 1, numberOfOutputs: 0, channelCount: 1 })
    worklet.port.onmessage = (event: MessageEvent<{ type?: string; pcm?: ArrayBuffer }>) => {
      if (event.data.type === 'pcm' && event.data.pcm) this.onFrame(new Int16Array(event.data.pcm))
      if (event.data.type === 'flush.complete') this.finishFlush()
    }
    source.connect(worklet)
    this.context = context
    this.source = source
    this.worklet = worklet
    if (context.state === 'suspended') await context.resume()
  }

  flush() {
    if (!this.worklet) return Promise.resolve()
    this.flushPromise ??= new Promise<void>((resolve) => {
      const timeout = window.setTimeout(resolve, 1_000)
      this.finishFlush = () => { window.clearTimeout(timeout); resolve() }
      this.worklet?.port.postMessage({ type: 'flush' })
    })
    return this.flushPromise
  }

  async close() {
    this.source?.disconnect()
    this.worklet?.disconnect()
    await this.context?.close()
    this.source = undefined
    this.worklet = undefined
    this.context = undefined
  }

  private finishFlush = () => {}
}

export function liveAudioSocketUrl(meetingId: string, audioOffsetMs: number) {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const query = new URLSearchParams({ audioOffsetMs: String(audioOffsetMs) })
  return `${protocol}//${window.location.host}/api/v1/meetings/${meetingId}/live?${query}`
}

function samplesToMs(samples: number) {
  return Math.round(samples * 1000 / SAMPLE_RATE)
}
