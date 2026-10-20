import type { LocalRecording, RecordingChunk, RecordingStore } from './recordingStore'

const STORAGE_FAILURE_MESSAGE = '브라우저 저장 실패 · 마이크를 중지했습니다. 저장된 녹음만 복구하세요. 마지막 청크가 누락됐을 수 있습니다.'

export interface RecordingTransport {
  upload(chunk: RecordingChunk, mimeType: string): Promise<unknown>
  complete(chunkCount: number): Promise<unknown>
}

export async function sha256(blob: Blob) {
  const bytes = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())
  return Array.from(new Uint8Array(bytes), (value) => value.toString(16).padStart(2, '0')).join('')
}

// Only acknowledged chunks are removed; network failures leave a durable retry queue.
export async function syncRecording(meetingId: string, store: RecordingStore, transport: RecordingTransport) {
  const session = await store.get(meetingId)
  if (!session) return false
  for (const chunk of await store.pending(meetingId)) {
    await transport.upload(chunk, session.mimeType)
    await store.removeChunk(meetingId, chunk.sequence)
  }
  // Re-read because recording can continue while an upload is in flight.
  const latest = await store.get(meetingId)
  if (latest?.stopped && (await store.pending(meetingId)).length === 0) {
    if (latest.chunkCount > 0) await transport.complete(latest.chunkCount)
    await store.removeSession(meetingId)
    return true
  }
  return false
}

export class MicrophoneRecording {
  private persist: Promise<void> = Promise.resolve()
  private uploading: Promise<boolean> | undefined
  private session: LocalRecording
  private startedAt = 0
  private stopped: Promise<void> | undefined
  private storageFailed = false

  constructor(
    private recorder: MediaRecorder,
    private stream: MediaStream,
    meetingId: string,
    private store: RecordingStore,
    private transport: RecordingTransport,
    private onChange: (message: string) => void,
  ) {
    this.session = { meetingId, mimeType: recorder.mimeType, chunkCount: 0, durationMs: 0, stopped: false }
  }

  async start() {
    await this.store.save(this.session)
    this.startedAt = performance.now()
    this.recorder.ondataavailable = ({ data }) => {
      if (!data.size) return
      const endMs = Math.max(this.session.durationMs + 1, Math.round(performance.now() - this.startedAt))
      this.persist = this.persist.then(async () => {
        const chunk = {
          meetingId: this.session.meetingId, sequence: this.session.chunkCount,
          startMs: this.session.durationMs, endMs, sha256: await sha256(data), blob: data,
        }
        const updated = { ...this.session, chunkCount: this.session.chunkCount + 1, durationMs: endMs }
        await this.store.save(updated, chunk)
        this.session = updated
        void this.sync()
      }).catch(() => {
        this.storageFailed = true
        this.onChange('브라우저 저장에 실패했습니다. 녹음을 중지합니다. 마지막 청크가 누락됐을 수 있습니다.')
        this.stopAutomatically()
      })
    }
    this.recorder.onerror = () => {
      this.onChange('마이크 녹음에 오류가 발생했습니다. 저장된 청크를 복구하세요.')
      this.stopAutomatically()
    }
    this.stream.getAudioTracks().forEach((track) => {
      track.onended = () => { this.onChange('마이크 연결이 끝났습니다. 저장된 녹음을 확인하세요.'); this.stopAutomatically() }
    })
    this.recorder.start(2000)
  }

  sync() {
    if (this.uploading) return this.uploading
    // A full store may also have rejected the stopped marker; retry it before finalization.
    const prepare = this.session.stopped
      ? this.store.get(this.session.meetingId).then((saved) => saved ? this.store.save(this.session) : undefined)
      : Promise.resolve()
    this.uploading = prepare.then(() => syncRecording(this.session.meetingId, this.store, this.transport))
      .then((complete) => { this.onChange(this.storageFailed ? STORAGE_FAILURE_MESSAGE : complete ? '녹음 처리 완료' : this.session.stopped ? '저장 상태를 확인해 주세요.' : '녹음 중 · 서버 동기화됨'); return complete })
      .catch(() => { this.onChange(this.storageFailed ? STORAGE_FAILURE_MESSAGE : '서버 연결 대기 · 브라우저에 녹음 보관 중'); return false })
      .finally(() => { this.uploading = undefined })
    return this.uploading
  }

  isActive() { return this.recorder.state !== 'inactive' }

  private stopAutomatically() {
    void this.stop().catch(() => {
      this.storageFailed = true
      this.onChange(STORAGE_FAILURE_MESSAGE)
    })
  }

  stop() {
    this.stopped ??= new Promise<void>((resolve, reject) => {
      const finish = async () => {
        try {
          await this.persist
          this.session = { ...this.session, stopped: true }
          await this.store.save(this.session)
          await this.uploading
          await this.sync()
          if (this.storageFailed) this.onChange(STORAGE_FAILURE_MESSAGE)
          resolve()
        } catch (error) { reject(error) }
        finally { this.stream.getTracks().forEach((track) => { track.onended = null; track.stop() }) }
      }
      this.recorder.onstop = () => {
        this.stream.getTracks().forEach((track) => { track.onended = null; track.stop() })
        void finish()
      }
      if (this.recorder.state === 'inactive') void finish()
      else this.recorder.stop()
    })
    return this.stopped
  }
}

export async function acquireRecordingLock(meetingId: string): Promise<() => void> {
  if (!navigator.locks) throw new Error('이 브라우저는 안전한 녹음 잠금을 지원하지 않습니다. 최신 Chrome을 사용하세요.')
  return new Promise((resolve, reject) => {
    void navigator.locks.request(`moa-recording-${meetingId}`, { ifAvailable: true }, async (lock) => {
      if (!lock) { reject(new Error('다른 탭에서 이 녹음을 사용 중입니다.')); return }
      await new Promise<void>((release) => resolve(release))
    }).catch(reject)
  })
}
