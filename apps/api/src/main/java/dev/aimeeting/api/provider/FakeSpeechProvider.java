package dev.aimeeting.api.provider;

import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.meeting.TranscriptSegment;
import dev.aimeeting.api.config.AppProperties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class FakeSpeechProvider implements SpeechProvider, AutoCloseable {
    private static final String EVENT_ID = "phase0-fake-final-1";
    private final MeetingRepository meetings;
    private final long eventDelayMs;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<UUID, UUID> claimedMeetings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicBoolean> cancelledSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> sessionMeetings = new ConcurrentHashMap<>();

    public FakeSpeechProvider(MeetingRepository meetings, AppProperties properties) {
        this.meetings = meetings;
        this.eventDelayMs = properties.fakeEventDelayMs();
    }

    @Override
    public void stream(UUID meetingId, UUID streamSessionId, Consumer<SpeechEvent> consumer) {
        cancelledSessions.put(streamSessionId, new AtomicBoolean());
        sessionMeetings.put(streamSessionId, meetingId);
        if (claimedMeetings.putIfAbsent(meetingId, streamSessionId) != null) {
            consumer.accept(SpeechEvent.sessionReady(meetingId, streamSessionId));
            executor.submit(() -> replayWhenComplete(meetingId, streamSessionId, consumer));
            return;
        }
        executor.submit(() -> emit(meetingId, streamSessionId, consumer));
    }

    private void emit(UUID meetingId, UUID streamSessionId, Consumer<SpeechEvent> consumer) {
        try {
            send(consumer, SpeechEvent.sessionReady(meetingId, streamSessionId));
            if (!meetings.transcript(meetingId).isEmpty()) {
                replayPersisted(meetingId, streamSessionId, consumer);
                meetings.stop(meetingId);
                send(consumer, event("stream.stopped", meetingId, streamSessionId, null, 99, null, "STOPPED"));
                meetings.closeStreamSession(streamSessionId, "REPLAY_COMPLETE");
                return;
            }
            pause();
            if (!stillStarted(meetingId, streamSessionId, consumer)) return;
            send(consumer, new SpeechEvent("transcript.partial", meetingId, streamSessionId, EVENT_ID, 1L,
                    0L, 1400L, "이번 주 배포 일정을", "speaker_0", "TENTATIVE", null, 0,
                    null, null, "PARTIAL"));
            pause();
            if (!stillStarted(meetingId, streamSessionId, consumer)) return;
            TranscriptSegment segment = meetings.insertFinal(meetingId, EVENT_ID, 2, 0, 2400,
                    "이번 주 배포 일정을 확인하겠습니다.", "speaker_0");
            send(consumer, event("transcript.final", meetingId, streamSessionId, segment, 2, segment.displayText(), "FINAL"));
            pause();
            if (!stillStarted(meetingId, streamSessionId, consumer)) return;
            segment = meetings.updateSpeaker(segment.id(), "speaker_0", "CONFIRMED");
            send(consumer, event("speaker.updated", meetingId, streamSessionId, segment, 3, segment.displayText(), "FINAL"));
            pause();
            if (!stillStarted(meetingId, streamSessionId, consumer)) return;
            segment = meetings.correct(meetingId, segment.id(), segment.revision(),
                    "이번 주 배포 일정을 확정하겠습니다.", "MOCK", "Phase 0 correction fixture", false);
            send(consumer, event("transcript.corrected", meetingId, streamSessionId, segment, 4, segment.displayText(), "CORRECTED"));
            pause();
            if (!stillStarted(meetingId, streamSessionId, consumer)) return;
            meetings.stop(meetingId);
            send(consumer, event("stream.stopped", meetingId, streamSessionId, segment, 5, null, "STOPPED"));
            meetings.closeStreamSession(streamSessionId, "FAKE_COMPLETE");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failStream(meetingId, streamSessionId, consumer);
        } catch (RuntimeException exception) {
            failStream(meetingId, streamSessionId, consumer);
        }
    }

    private SpeechEvent event(String type, UUID meetingId, UUID sessionId, TranscriptSegment segment,
                              long sequence, String text, String status) {
        return new SpeechEvent(type, meetingId, sessionId, segment == null ? null : segment.eventId(), sequence,
                segment == null ? null : segment.startMs(), segment == null ? null : segment.endMs(), text,
                segment == null ? null : segment.speakerLabel(), segment == null ? null : segment.speakerStatus(),
                segment == null ? null : segment.id(), segment == null ? null : segment.revision(),
                segment == null ? null : segment.originalText(), segment == null ? null : segment.displayText(), status);
    }

    private void send(Consumer<SpeechEvent> consumer, SpeechEvent event) { consumer.accept(event); }
    private void pause() throws InterruptedException { Thread.sleep(eventDelayMs); }

    private boolean stillStarted(UUID meetingId, UUID streamSessionId, Consumer<SpeechEvent> consumer) {
        AtomicBoolean cancelled = cancelledSessions.get(streamSessionId);
        if (cancelled == null || cancelled.get()) return false;
        if ("STARTED".equals(meetings.find(meetingId).status())) return true;
        send(consumer, event("stream.stopped", meetingId, streamSessionId, null, 5, null, "STOPPED_BY_USER"));
        meetings.closeStreamSession(streamSessionId, "STOPPED_BY_USER");
        return false;
    }

    private void replayWhenComplete(UUID meetingId, UUID streamSessionId, Consumer<SpeechEvent> consumer) {
        try {
            while ("STARTED".equals(meetings.find(meetingId).status())) {
                AtomicBoolean cancelled = cancelledSessions.get(streamSessionId);
                if (cancelled == null || cancelled.get()) return;
                Thread.sleep(50);
            }
            AtomicBoolean cancelled = cancelledSessions.get(streamSessionId);
            if (cancelled == null || cancelled.get()) return;
            replayPersisted(meetingId, streamSessionId, consumer);
            send(consumer, event("stream.stopped", meetingId, streamSessionId, null, 99, null, "STOPPED"));
            meetings.closeStreamSession(streamSessionId, "REPLAY_COMPLETE");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            failStream(meetingId, streamSessionId, consumer);
        } finally {
            cancelledSessions.remove(streamSessionId);
        }
    }

    private void replayPersisted(UUID meetingId, UUID streamSessionId, Consumer<SpeechEvent> consumer) {
        for (TranscriptSegment segment : meetings.transcript(meetingId)) {
            send(consumer, event("transcript.final", meetingId, streamSessionId, segment, segment.sequence(),
                    segment.originalText(), "FINAL"));
            if (segment.revision() > 0) send(consumer, event("transcript.corrected", meetingId, streamSessionId,
                    segment, segment.sequence() + 1, segment.displayText(), segment.transcriptStatus()));
        }
    }

    @Override
    public void stop(UUID streamSessionId) {
        AtomicBoolean cancelled = cancelledSessions.remove(streamSessionId);
        if (cancelled != null) cancelled.set(true);
        UUID meetingId = sessionMeetings.remove(streamSessionId);
        if (meetingId != null) claimedMeetings.remove(meetingId, streamSessionId);
    }

    @Override
    public void stopMeeting(UUID meetingId) {
        sessionMeetings.forEach((streamSessionId, mappedMeetingId) -> {
            if (!meetingId.equals(mappedMeetingId)) return;
            stop(streamSessionId);
            meetings.closeStreamSession(streamSessionId, "NO_LIVE_CLIENTS");
        });
    }

    private void failStream(UUID meetingId, UUID streamSessionId, Consumer<SpeechEvent> consumer) {
        safeSend(consumer, event("stream.error", meetingId, streamSessionId, null, 98, null, "INTERNAL_ERROR"));
        try {
            if ("STARTED".equals(meetings.find(meetingId).status())) meetings.stop(meetingId);
        } catch (RuntimeException ignored) {
            // Failure details and transcript content are intentionally not exposed.
        } finally {
            safeSend(consumer, event("stream.stopped", meetingId, streamSessionId, null, 99, null, "ERROR"));
            try {
                meetings.closeStreamSession(streamSessionId, "ERROR");
            } catch (RuntimeException ignored) {
                // Cleanup continues even if the database is unavailable.
            }
            claimedMeetings.remove(meetingId, streamSessionId);
            cancelledSessions.remove(streamSessionId);
            sessionMeetings.remove(streamSessionId);
        }
    }

    private void safeSend(Consumer<SpeechEvent> consumer, SpeechEvent event) {
        try {
            send(consumer, event);
        } catch (RuntimeException ignored) {
            // A dead client must not prevent provider and database cleanup.
        }
    }

    @Override public void close() { executor.close(); }
}
