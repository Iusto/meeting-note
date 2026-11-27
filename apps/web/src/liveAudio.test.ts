import { describe, expect, it, vi } from 'vitest'
import { LivePcmTransport } from './liveAudio'

class FakeSocket {
  binaryType: BinaryType = 'blob'
  bufferedAmount = 0
  readyState = 1
  onopen: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null
  sent: (string | ArrayBuffer)[] = []

  send(data: string | ArrayBuffer) { this.sent.push(data) }
  close() { this.readyState = 3 }
  message(payload: object) { this.onmessage?.({ data: JSON.stringify(payload) } as MessageEvent) }
  disconnected() { this.readyState = 3; this.onclose?.({} as CloseEvent) }
}

describe('LivePcmTransport', () => {
  it('replaces an OPEN but unresponsive socket after an outage without replaying audio', () => {
    let now = 0
    const sockets: FakeSocket[] = []
    const offsets: number[] = []
    const scheduled: (() => void)[] = []
    const dropped = vi.fn()
    const transport = new LivePcmTransport({
      meetingId: 'meeting', now: () => now, socketUrl: (_, offset) => { offsets.push(offset); return 'ws://live' },
      onEvent: vi.fn(), onDropped: dropped,
      createSocket: () => { const socket = new FakeSocket(); sockets.push(socket); return socket },
      schedule: callback => { scheduled.push(callback); return scheduled.length }, cancelSchedule: vi.fn(),
    })
    transport.start()
    sockets[0].message({ type: 'session.ready', meetingId: 'meeting' })
    transport.accept(new Int16Array(1_600))
    now = 5_000
    transport.accept(new Int16Array(1_600))
    expect(sockets[0].sent).toContain(JSON.stringify({ type: 'stream.ping' }))
    now = 15_000
    transport.accept(new Int16Array(1_600))
    expect(sockets[0].readyState).toBe(3)
    scheduled.shift()?.()
    sockets[1].message({ type: 'session.ready', meetingId: 'meeting' })
    transport.accept(new Int16Array(1_600).fill(7))
    expect(offsets).toEqual([0, 300])
    expect(dropped).toHaveBeenCalledWith({ startMs: 200, endMs: 300, reason: 'disconnected' })
    expect(new Int16Array(sockets[1].sent[0] as ArrayBuffer)[0]).toBe(7)
  })

  it('keeps a silent but responsive stream alive and waits for previous-session cleanup', () => {
    let now = 0
    const sockets: FakeSocket[] = []
    const scheduled: (() => void)[] = []
    const onEvent = vi.fn()
    const transport = new LivePcmTransport({
      meetingId: 'meeting', now: () => now, socketUrl: () => 'ws://live', onEvent, onDropped: vi.fn(),
      createSocket: () => { const socket = new FakeSocket(); sockets.push(socket); return socket },
      schedule: callback => { scheduled.push(callback); return scheduled.length }, cancelSchedule: vi.fn(),
    })
    transport.start()
    sockets[0].message({ type: 'session.ready', meetingId: 'meeting' })
    for (now = 5_000; now <= 30_000; now += 5_000) {
      transport.accept(new Int16Array(1_600))
      sockets[0].message({ type: 'stream.pong', meetingId: 'meeting' })
    }
    expect(sockets).toHaveLength(1)
    expect(onEvent).toHaveBeenCalledTimes(1)
    sockets[0].disconnected()
    scheduled.shift()?.()
    sockets[1].message({ type: 'stream.retry', meetingId: 'meeting' })
    expect(sockets[1].readyState).toBe(3)
    scheduled.shift()?.()
    sockets[2].message({ type: 'session.ready', meetingId: 'meeting' })
    transport.accept(new Int16Array(1_600))
    expect(sockets[2].sent[0]).toBeInstanceOf(ArrayBuffer)
  })

  it('bounds automatic retries', async () => {
    const sockets: FakeSocket[] = []
    const callbacks = new Map<number, () => void>()
    let timerId = 0
    const onEvent = vi.fn()
    const transport = new LivePcmTransport({
      meetingId: 'meeting', socketUrl: () => 'ws://live', onEvent, onDropped: vi.fn(),
      createSocket: () => { const socket = new FakeSocket(); sockets.push(socket); return socket },
      schedule: callback => { callbacks.set(++timerId, callback); return timerId },
      cancelSchedule: id => { callbacks.delete(id) },
    })
    transport.start()
    for (let attempt = 0; attempt <= 12; attempt++) {
      sockets.at(-1)!.message({ type: 'stream.retry', meetingId: 'meeting' })
      const next = callbacks.entries().next().value
      if (next) { callbacks.delete(next[0]); next[1]() }
    }
    expect(sockets).toHaveLength(13)
    expect(onEvent).toHaveBeenLastCalledWith(expect.objectContaining({ type: 'stream.error' }))
    expect(callbacks.size).toBe(0)
    await transport.stop()
    expect(callbacks.size).toBe(0)
  })

  it('cancels a pending cleanup retry when recording stops', async () => {
    const socket = new FakeSocket()
    const schedule = vi.fn(() => 7)
    const cancelSchedule = vi.fn()
    const createSocket = vi.fn(() => socket)
    const transport = new LivePcmTransport({
      meetingId: 'meeting', socketUrl: () => 'ws://live', onEvent: vi.fn(), onDropped: vi.fn(),
      createSocket, schedule, cancelSchedule,
    })
    transport.start()
    socket.message({ type: 'stream.retry', meetingId: 'meeting' })
    await transport.stop()
    expect(cancelSchedule).toHaveBeenCalledWith(7)
    expect(createSocket).toHaveBeenCalledTimes(1)
  })

  it('reconnects from the current capture offset without replaying already-sent audio', () => {
    const sockets: FakeSocket[] = []
    const urls: string[] = []
    const scheduled: (() => void)[] = []
    const dropped = vi.fn()
    const transport = new LivePcmTransport({
      meetingId: 'meeting',
      socketUrl: (_, offset) => `ws://live?audioOffsetMs=${offset}`,
      onEvent: vi.fn(), onDropped: dropped,
      createSocket: (url) => { urls.push(url); const socket = new FakeSocket(); sockets.push(socket); return socket },
      schedule: (callback) => { scheduled.push(callback); return scheduled.length },
      cancelSchedule: vi.fn(),
    })

    transport.start()
    transport.accept(new Int16Array(1_600).fill(1))
    sockets[0].message({ type: 'session.ready', meetingId: 'meeting' })
    transport.accept(new Int16Array(1_600).fill(2))
    sockets[0].disconnected()
    transport.accept(new Int16Array(1_600).fill(3))
    scheduled.shift()?.()
    transport.accept(new Int16Array(1_600).fill(4))
    sockets[1].message({ type: 'session.ready', meetingId: 'meeting' })

    expect(urls).toEqual(['ws://live?audioOffsetMs=0', 'ws://live?audioOffsetMs=300'])
    expect(sockets[0].sent).toHaveLength(2)
    expect(sockets[1].sent).toHaveLength(1)
    expect(new Int16Array(sockets[1].sent[0] as ArrayBuffer)[0]).toBe(4)
    expect(dropped).toHaveBeenCalledWith({ startMs: 200, endMs: 300, reason: 'disconnected' })
  })

  it('drops a bounded connecting queue and resumes at the next uncaptured offset', () => {
    const sockets: FakeSocket[] = []
    const scheduled: (() => void)[] = []
    const dropped = vi.fn()
    const urls: number[] = []
    const transport = new LivePcmTransport({
      meetingId: 'meeting', socketUrl: (_, offset) => { urls.push(offset); return `ws://${offset}` },
      onEvent: vi.fn(), onDropped: dropped, maxQueuedSamples: 1_600,
      createSocket: () => { const socket = new FakeSocket(); sockets.push(socket); return socket },
      schedule: (callback) => { scheduled.push(callback); return scheduled.length }, cancelSchedule: vi.fn(),
    })

    transport.start()
    transport.accept(new Int16Array(1_600))
    transport.accept(new Int16Array(1_600))
    scheduled.shift()?.()

    expect(dropped).toHaveBeenCalledWith({ startMs: 0, endMs: 200, reason: 'queue-full' })
    expect(urls).toEqual([0, 200])
    expect(sockets[0].sent).toEqual([])
  })

  it('flushes queued PCM before requesting provider stop', async () => {
    const socket = new FakeSocket()
    const transport = new LivePcmTransport({
      meetingId: 'meeting', socketUrl: () => 'ws://live', onEvent: vi.fn(), onDropped: vi.fn(),
      createSocket: () => socket, schedule: () => 1, cancelSchedule: vi.fn(),
    })
    transport.start()
    transport.accept(new Int16Array(800).fill(7))
    socket.message({ type: 'session.ready', meetingId: 'meeting' })
    const stopping = transport.stop()
    socket.message({ type: 'stream.stopped', meetingId: 'meeting' })
    await stopping

    expect(socket.sent).toHaveLength(2)
    expect(socket.sent[0]).toBeInstanceOf(ArrayBuffer)
    expect(socket.sent[1]).toBe(JSON.stringify({ type: 'stream.stop' }))
  })

  it('stops sending PCM after a terminal provider error while leaving capture ownership to the recorder', () => {
    const socket = new FakeSocket()
    const onEvent = vi.fn()
    const transport = new LivePcmTransport({
      meetingId: 'meeting', socketUrl: () => 'ws://live', onEvent, onDropped: vi.fn(),
      createSocket: () => socket, schedule: vi.fn(() => 1), cancelSchedule: vi.fn(),
    })
    transport.start()
    socket.message({ type: 'session.ready', meetingId: 'meeting' })
    transport.accept(new Int16Array(800).fill(1))
    socket.message({ type: 'stream.error', meetingId: 'meeting', message: 'provider unavailable' })
    transport.accept(new Int16Array(800).fill(2))

    expect(onEvent).toHaveBeenCalledWith(expect.objectContaining({ type: 'stream.error' }))
    expect(socket.sent).toHaveLength(1)
    expect(socket.readyState).toBe(3)
  })

  it('drops audio captured while disconnected and reconnects at the end of that gap', () => {
    const sockets: FakeSocket[] = []
    const scheduled: (() => void)[] = []
    const urls: number[] = []
    const dropped = vi.fn()
    const transport = new LivePcmTransport({
      meetingId: 'meeting', socketUrl: (_, offset) => { urls.push(offset); return `ws://${offset}` },
      onEvent: vi.fn(), onDropped: dropped,
      createSocket: () => { const socket = new FakeSocket(); sockets.push(socket); return socket },
      schedule: (callback) => { scheduled.push(callback); return scheduled.length }, cancelSchedule: vi.fn(),
    })
    transport.start()
    sockets[0].message({ type: 'session.ready', meetingId: 'meeting' })
    transport.accept(new Int16Array(1_600).fill(1))
    sockets[0].disconnected()
    transport.accept(new Int16Array(3_200).fill(2))
    scheduled.shift()?.()
    sockets[1].message({ type: 'session.ready', meetingId: 'meeting' })
    transport.accept(new Int16Array(1_600).fill(3))

    expect(dropped).toHaveBeenCalledWith({ startMs: 100, endMs: 300, reason: 'disconnected' })
    expect(urls).toEqual([0, 300])
    expect(sockets[1].sent).toHaveLength(1)
    expect(new Int16Array(sockets[1].sent[0] as ArrayBuffer)[0]).toBe(3)
  })

  it('keeps meeting-relative time for a late microphone start, reconnect, and dropped gap', () => {
    const sockets: FakeSocket[] = []
    const scheduled: (() => void)[] = []
    const urls: number[] = []
    const dropped = vi.fn()
    const transport = new LivePcmTransport({
      meetingId: 'meeting', baseOffsetMs: 12_500,
      socketUrl: (_, offset) => { urls.push(offset); return `ws://${offset}` },
      onEvent: vi.fn(), onDropped: dropped,
      createSocket: () => { const socket = new FakeSocket(); sockets.push(socket); return socket },
      schedule: (callback) => { scheduled.push(callback); return scheduled.length }, cancelSchedule: vi.fn(),
    })
    transport.start()
    sockets[0].message({ type: 'session.ready', meetingId: 'meeting' })
    transport.accept(new Int16Array(1_600).fill(1))
    sockets[0].disconnected()
    transport.accept(new Int16Array(3_200).fill(2))
    scheduled.shift()?.()
    sockets[1].message({ type: 'session.ready', meetingId: 'meeting' })
    transport.accept(new Int16Array(1_600).fill(3))

    expect(urls).toEqual([12_500, 12_800])
    expect(dropped).toHaveBeenCalledWith({ startMs: 12_600, endMs: 12_800, reason: 'disconnected' })
    expect(sockets[1].sent).toHaveLength(1)
  })
})
