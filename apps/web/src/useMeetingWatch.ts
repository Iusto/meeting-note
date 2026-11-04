import { useEffect, useRef, useState } from 'react'
import { api, watchSocketUrl } from './api'
import type { SpeakerName, SpeakerOverride } from './api'
import type { LiveEvent, Meeting, TranscriptSegment } from './types'

interface Snapshot {
  meeting: Meeting
  segments: TranscriptSegment[]
  speakers: SpeakerName[]
  overrides: SpeakerOverride[]
}

export function useMeetingWatch(meetingId: string, callbacks: {
  snapshot: (value: Snapshot) => void
  event: (value: LiveEvent) => void
  disconnected: () => void
  error: (error: unknown) => void
}) {
  const current = useRef(callbacks)
  current.current = callbacks
  const [status, setStatus] = useState('실시간 보기 연결 중')

  useEffect(() => {
    let active = true
    let socket: WebSocket | undefined
    let retry: number | undefined
    let refreshing = false
    let refreshAgain = false

    async function refresh() {
      if (!active) return
      if (refreshing) { refreshAgain = true; return }
      refreshing = true
      try {
        const [meeting, segments, speakers, overrides] = await Promise.all([
          api.getMeeting(meetingId), api.getTranscript(meetingId),
          api.getSpeakerNames(meetingId), api.getSpeakerOverrides(meetingId),
        ])
        if (active) current.current.snapshot({ meeting, segments, speakers, overrides })
      } catch (error) { if (active) current.current.error(error) }
      finally {
        refreshing = false
        if (active && refreshAgain) { refreshAgain = false; void refresh() }
      }
    }

    function connect() {
      if (!active) return
      socket = new WebSocket(watchSocketUrl(meetingId))
      socket.onmessage = message => {
        if (!active) return
        try {
          const event = JSON.parse(message.data) as LiveEvent
          if (event.meetingId !== meetingId) return
          if (event.type === 'session.ready') {
            setStatus('실시간 보기 연결됨')
            // Subscribe first, then reload storage to recover anything missed offline.
            void refresh()
          } else if (event.type === 'meeting.changed' || event.type === 'stream.stopped') void refresh()
          else current.current.event(event)
        } catch { void refresh() }
      }
      socket.onerror = () => socket?.close()
      socket.onclose = () => {
        if (!active) return
        current.current.disconnected()
        setStatus('실시간 보기 재연결 중')
        retry = window.setTimeout(connect, 1500)
      }
    }

    connect()
    void refresh()
    // GET only: recover metadata, saved summaries and background-tab updates.
    const timer = window.setInterval(() => { void refresh() }, 15_000)
    const focus = () => { void refresh() }
    window.addEventListener('focus', focus)
    window.addEventListener('online', focus)
    return () => {
      active = false
      window.clearTimeout(retry)
      window.clearInterval(timer)
      window.removeEventListener('focus', focus)
      window.removeEventListener('online', focus)
      socket?.close()
    }
  }, [meetingId])

  return status
}
