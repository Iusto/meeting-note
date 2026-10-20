export interface LocalRecording {
  meetingId: string
  mimeType: string
  chunkCount: number
  durationMs: number
  stopped: boolean
}

export interface RecordingChunk {
  meetingId: string
  sequence: number
  startMs: number
  endMs: number
  sha256: string
  blob: Blob
}

export interface RecordingStore {
  get(meetingId: string): Promise<LocalRecording | undefined>
  save(session: LocalRecording, chunk?: RecordingChunk): Promise<void>
  pending(meetingId: string): Promise<RecordingChunk[]>
  removeChunk(meetingId: string, sequence: number): Promise<void>
  removeSession(meetingId: string): Promise<void>
}

let database: Promise<IDBDatabase> | undefined
function openDatabase() {
  database ??= new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open('moa-recordings', 1)
    request.onupgradeneeded = () => {
      request.result.createObjectStore('sessions', { keyPath: 'meetingId' })
      const chunks = request.result.createObjectStore('chunks', { keyPath: ['meetingId', 'sequence'] })
      chunks.createIndex('meetingId', 'meetingId')
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => { database = undefined; reject(request.error) }
    request.onblocked = () => { database = undefined; reject(new Error('다른 탭의 녹음 저장소를 닫아 주세요.')) }
  })
  return database
}

async function transaction<T>(names: string[], mode: IDBTransactionMode, action: (tx: IDBTransaction, result: (value: T) => void) => void): Promise<T> {
  const db = await openDatabase()
  return new Promise((resolve, reject) => {
    const tx = db.transaction(names, mode)
    let value: T
    tx.oncomplete = () => resolve(value)
    tx.onabort = () => reject(tx.error ?? new Error('녹음 저장 공간이 부족하거나 저장이 중단됐습니다.'))
    tx.onerror = () => reject(tx.error)
    action(tx, (result) => { value = result })
  })
}

export const recordingStore: RecordingStore = {
  get: (meetingId) => transaction(['sessions'], 'readonly', (tx, result) => {
    tx.objectStore('sessions').get(meetingId).onsuccess = (event) => result((event.target as IDBRequest<LocalRecording | undefined>).result)
  }),
  save: (session, chunk) => transaction(['sessions', 'chunks'], 'readwrite', (tx) => {
    tx.objectStore('sessions').put(session)
    if (chunk) tx.objectStore('chunks').put(chunk)
  }),
  pending: (meetingId) => transaction(['chunks'], 'readonly', (tx, result) => {
    tx.objectStore('chunks').index('meetingId').getAll(meetingId).onsuccess = (event) => {
      result(((event.target as IDBRequest<RecordingChunk[]>).result).sort((a, b) => a.sequence - b.sequence))
    }
  }),
  removeChunk: (meetingId, sequence) => transaction(['chunks'], 'readwrite', (tx) => {
    tx.objectStore('chunks').delete([meetingId, sequence])
  }),
  removeSession: (meetingId) => transaction(['sessions'], 'readwrite', (tx) => { tx.objectStore('sessions').delete(meetingId) }),
}

export function removeLocalRecording(meetingId: string) {
  return transaction<void>(['sessions', 'chunks'], 'readwrite', (tx) => {
    tx.objectStore('sessions').delete(meetingId)
    const chunks = tx.objectStore('chunks')
    const cursor = chunks.index('meetingId').openKeyCursor(IDBKeyRange.only(meetingId))
    cursor.onsuccess = () => {
      if (!cursor.result) return
      chunks.delete(cursor.result.primaryKey)
      cursor.result.continue()
    }
  })
}
