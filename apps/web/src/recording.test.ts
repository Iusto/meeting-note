import { describe, expect, it, vi } from 'vitest'
import { MicrophoneRecording, sha256, syncRecording } from './recording'
import type { LocalRecording, RecordingChunk, RecordingStore } from './recordingStore'

function memoryStore() {
  let session: LocalRecording | undefined = { meetingId: 'meeting', mimeType: 'audio/webm', chunkCount: 2, durationMs: 4000, stopped: true }
  const chunks: RecordingChunk[] = [0, 1].map((sequence) => ({ meetingId: 'meeting', sequence, startMs: sequence * 2000, endMs: (sequence + 1) * 2000, blob: new Blob([String(sequence)]), sha256: String(sequence) }))
  const store: RecordingStore = {
    get: async () => session,
    save: async (next, chunk) => { session = next; if (chunk) chunks.push(chunk) },
    pending: async () => [...chunks],
    removeChunk: async (_, sequence) => { chunks.splice(chunks.findIndex((chunk) => chunk.sequence === sequence), 1) },
    removeSession: async () => { session = undefined },
  }
  return { store, chunks }
}

describe('durable recording uploads', () => {
  it('keeps failed chunks and retries only unacknowledged data before completing', async () => {
    const { store, chunks } = memoryStore()
    const upload = vi.fn().mockResolvedValueOnce({}).mockRejectedValueOnce(new Error('offline')).mockResolvedValue({})
    const complete = vi.fn().mockResolvedValue({})
    await expect(syncRecording('meeting', store, { upload, complete })).rejects.toThrow('offline')
    expect(chunks.map((chunk) => chunk.sequence)).toEqual([1])
    expect(complete).not.toHaveBeenCalled()
    expect(await store.get('meeting')).toBeDefined()
    expect(await syncRecording('meeting', store, { upload, complete })).toBe(true)
    expect(upload.mock.calls.map(([chunk]) => chunk.sequence)).toEqual([0, 1, 1])
    expect(complete).toHaveBeenCalledWith(2)
    expect(await store.get('meeting')).toBeUndefined()
  })

  it('does not finalize an ongoing recording or delete the session after failed completion', async () => {
    const { store } = memoryStore()
    const complete = vi.fn().mockRejectedValue(new Error('server unavailable'))
    const session = (await store.get('meeting'))!
    await store.save({ ...session, stopped: false })
    expect(await syncRecording('meeting', store, { upload: async () => {}, complete })).toBe(false)
    expect(complete).not.toHaveBeenCalled()
    await store.save({ ...session, stopped: true })
    await expect(syncRecording('meeting', store, { upload: async () => {}, complete })).rejects.toThrow()
    expect(await store.get('meeting')).toBeDefined()
  })

  it('hashes the audio bytes using SHA-256', async () => {
    expect(await sha256(new Blob(['abc']))).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
  })

  it('releases an empty interrupted session without requesting an empty server file', async () => {
    const { store, chunks } = memoryStore()
    chunks.length = 0
    await store.save({ ...(await store.get('meeting'))!, chunkCount: 0 })
    const complete = vi.fn()
    expect(await syncRecording('meeting', store, { upload: vi.fn(), complete })).toBe(true)
    expect(complete).not.toHaveBeenCalled()
    expect(await store.get('meeting')).toBeUndefined()
  })

  it('persists the final recorder event before completing and releases microphone tracks', async () => {
    const { store, chunks } = memoryStore()
    await store.removeSession('meeting')
    chunks.length = 0
    const upload = vi.fn().mockResolvedValue({})
    const complete = vi.fn().mockResolvedValue({})
    const track = { stop: vi.fn(), onended: null }
    const recorder = {
      mimeType: 'audio/webm', state: 'inactive',
      ondataavailable: (event: { data: Blob }) => { void event }, onstop: () => {},
      start: vi.fn(() => { recorder.state = 'recording' }),
      stop: () => {
        recorder.state = 'inactive'
        queueMicrotask(() => { recorder.ondataavailable({ data: new Blob(['last']) }); recorder.onstop() })
      },
    }
    const stream = { getTracks: () => [track], getAudioTracks: () => [track] }
    const engine = new MicrophoneRecording(recorder as unknown as MediaRecorder, stream as unknown as MediaStream, 'meeting', store, { upload, complete }, () => {})
    await engine.start()
    await engine.stop()
    expect(recorder.start).toHaveBeenCalledWith(2000)
    expect(complete).toHaveBeenCalledWith(1)
    expect(upload).toHaveBeenCalledTimes(1)
    expect(upload.mock.calls[0][0].sequence).toBe(0)
    expect(await upload.mock.calls[0][0].blob.text()).toBe('last')
    expect(track.stop).toHaveBeenCalled()
    expect(await store.get('meeting')).toBeUndefined()
  })

  it('stops microphone tracks even when saving the session fails on stop', async () => {
    const { store, chunks } = memoryStore()
    chunks.length = 0
    const track = { stop: vi.fn(), onended: null }
    const recorder = {
      mimeType: 'audio/webm', state: 'inactive', onstop: () => {},
      start: () => { recorder.state = 'recording' },
      stop: () => { recorder.state = 'inactive'; queueMicrotask(() => recorder.onstop()) },
    }
    const engine = new MicrophoneRecording(recorder as unknown as MediaRecorder, { getTracks: () => [track], getAudioTracks: () => [track] } as unknown as MediaStream, 'meeting', store, { upload: vi.fn(), complete: vi.fn() }, () => {})
    await engine.start()
    store.save = async () => { throw new Error('quota exceeded') }
    await expect(engine.stop()).rejects.toThrow('quota exceeded')
    expect(track.stop).toHaveBeenCalled()
    expect(engine.isActive()).toBe(false)
  })

  it('stops safely and reports lost audio when storage fills during recording', async () => {
    const { store, chunks } = memoryStore()
    chunks.length = 0
    const track = { stop: vi.fn(), onended: null }
    const recorder = {
      mimeType: 'audio/webm', state: 'inactive', onstop: () => {},
      ondataavailable: (event: { data: Blob }) => { void event },
      start: () => { recorder.state = 'recording' },
      stop: vi.fn(() => { recorder.state = 'inactive'; queueMicrotask(() => recorder.onstop()) }),
    }
    const message = vi.fn()
    const upload = vi.fn()
    const complete = vi.fn()
    const engine = new MicrophoneRecording(recorder as unknown as MediaRecorder,
      { getTracks: () => [track], getAudioTracks: () => [track] } as unknown as MediaStream,
      'meeting', store, { upload, complete }, message)
    await engine.start()
    // Once quota is exhausted, even saving the stopped session can fail.
    const save = store.save
    store.save = async () => { throw new DOMException('Synthetic storage full', 'QuotaExceededError') }
    recorder.ondataavailable({ data: new Blob(['unpersisted audio']) })
    await vi.waitFor(() => expect(track.stop).toHaveBeenCalled())
    expect(engine.isActive()).toBe(false)
    expect(recorder.stop).toHaveBeenCalledTimes(1)
    expect(chunks).toEqual([])
    expect(upload).not.toHaveBeenCalled()
    expect(complete).not.toHaveBeenCalled()
    await vi.waitFor(() => expect(message).toHaveBeenLastCalledWith(
      '브라우저 저장 실패 · 마이크를 중지했습니다. 저장된 녹음만 복구하세요. 마지막 청크가 누락됐을 수 있습니다.'))
    store.save = save
    expect(await engine.sync()).toBe(true)
    expect(await store.get('meeting')).toBeUndefined()
    expect(complete).not.toHaveBeenCalled()
    expect(message.mock.calls.at(-1)?.[0]).toContain('마지막 청크가 누락됐을 수 있습니다.')
    expect(await engine.sync()).toBe(false)
    expect(await store.get('meeting')).toBeUndefined()
  })

  it('retries completion after a server failure without reuploading acknowledged chunks', async () => {
    const { store, chunks } = memoryStore()
    const upload = vi.fn().mockResolvedValue({})
    const complete = vi.fn().mockRejectedValueOnce(new Error('Synthetic restart')).mockResolvedValue({})
    await expect(syncRecording('meeting', store, { upload, complete })).rejects.toThrow('Synthetic restart')
    expect(chunks).toEqual([])
    expect(await store.get('meeting')).toMatchObject({ stopped: true, chunkCount: 2 })
    expect(await syncRecording('meeting', store, { upload, complete })).toBe(true)
    expect(upload).toHaveBeenCalledTimes(2)
    expect(complete).toHaveBeenCalledTimes(2)
    expect(await store.get('meeting')).toBeUndefined()
  })
})
