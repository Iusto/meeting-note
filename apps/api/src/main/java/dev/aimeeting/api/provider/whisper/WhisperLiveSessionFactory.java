package dev.aimeeting.api.provider.whisper;

import dev.aimeeting.api.config.AiConfiguration;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.meeting.MeetingRepository.FinalSegmentInput;
import dev.aimeeting.api.provider.LiveSpeechSession;
import dev.aimeeting.api.provider.SpeechEvent;
import dev.aimeeting.api.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class WhisperLiveSessionFactory {
    private static final int BYTES_PER_MS = 32;
    private static final int WINDOW_BYTES = 8_000 * BYTES_PER_MS;
    private final ConcurrentHashMap<UUID, Session> active = new ConcurrentHashMap<>();
    private final MeetingRepository meetings;
    private final AiConfiguration configuration;
    private final WhisperClient client;

    public WhisperLiveSessionFactory(MeetingRepository meetings, AiConfiguration configuration, WhisperClient client) {
        this.meetings = meetings;
        this.configuration = configuration;
        this.client = client;
    }

    public CompletableFuture<LiveSpeechSession> open(UUID meetingId, long audioOffsetMs, Consumer<SpeechEvent> events) {
        configuration.requireSpeech();
        long remainingMs = configuration.transcriptionMaximumSeconds() * 1000L - audioOffsetMs;
        if (remainingMs <= 0) return CompletableFuture.failedFuture(new ApiException(HttpStatus.CONFLICT,
                "AUDIO_LIMIT_EXCEEDED", "Meeting audio duration limit reached"));
        var session = new Session(meetingId, audioOffsetMs, remainingMs * BYTES_PER_MS, events);
        if (active.putIfAbsent(meetingId, session) != null) {
            return CompletableFuture.failedFuture(new ApiException(HttpStatus.CONFLICT,
                    "TRANSCRIPTION_RECOVERING", "Previous transcription is still finalizing"));
        }
        try {
            client.requireReady();
            session.sessionId = meetings.createStreamSession(meetingId, "WHISPER", audioOffsetMs);
            session.worker.start();
            return CompletableFuture.completedFuture(session);
        } catch (RuntimeException failure) {
            active.remove(meetingId, session);
            return CompletableFuture.failedFuture(failure);
        }
    }

    private final class Session implements LiveSpeechSession {
        private final UUID meetingId;
        private final long maximumBytes;
        private final Consumer<SpeechEvent> events;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(WINDOW_BYTES);
        private final ArrayBlockingQueue<Window> windows = new ArrayBlockingQueue<>(4);
        private final CompletableFuture<Outcome> finished = new CompletableFuture<>();
        private final Thread worker = Thread.ofVirtual().name("whisper-live").unstarted(this::run);
        private final long audioOffsetMs;
        private UUID sessionId;
        private long acceptedBytes;
        private long queuedBytes;
        private long sequence;
        private volatile boolean ending;
        private volatile boolean cancelled;
        private volatile boolean limitReached;

        private Session(UUID meetingId, long audioOffsetMs, long maximumBytes, Consumer<SpeechEvent> events) {
            this.meetingId = meetingId;
            this.audioOffsetMs = audioOffsetMs;
            this.maximumBytes = maximumBytes;
            this.events = events;
        }

        @Override public UUID streamSessionId() { return sessionId; }

        @Override
        public synchronized CompletableFuture<Void> sendAudio(byte[] pcm) {
            if (ending) return CompletableFuture.completedFuture(null);
            if (pcm.length == 0 || pcm.length % 2 != 0 || pcm.length > 65_536) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid PCM16 audio frame"));
            }
            int allowed = (int) Math.min(pcm.length, maximumBytes - acceptedBytes);
            for (int index = 0; index < allowed;) {
                int length = Math.min(allowed - index, WINDOW_BYTES - buffer.size());
                buffer.write(pcm, index, length);
                index += length;
                if (buffer.size() == WINDOW_BYTES && !enqueue()) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Whisper is behind the live stream"));
                }
            }
            acceptedBytes += allowed;
            if (acceptedBytes == maximumBytes) {
                limitReached = true;
                finish();
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public synchronized CompletableFuture<?> finish() {
            if (!ending) {
                ending = true;
                // The worker drains all complete windows first, then takes the final buffer under the same lock.
            }
            return finished.copy();
        }

        @Override public CompletableFuture<Outcome> outcome() { return finished.copy(); }

        @Override
        public synchronized void close() {
            cancelled = true;
            ending = true;
            worker.interrupt();
        }

        private boolean enqueue() {
            byte[] pcm = buffer.toByteArray();
            if (!windows.offer(new Window(audioOffsetMs + queuedBytes / BYTES_PER_MS, pcm))) return false;
            queuedBytes += pcm.length;
            buffer.reset();
            return true;
        }

        private synchronized Window finalWindow() {
            if (buffer.size() == 0) return null;
            var tail = new Window(audioOffsetMs + queuedBytes / BYTES_PER_MS, buffer.toByteArray());
            buffer.reset();
            return tail;
        }

        private void run() {
            Outcome outcome = new Outcome("USER_STOP", false);
            try {
                while (!cancelled) {
                    var window = windows.poll(200, TimeUnit.MILLISECONDS);
                    if (window != null) {
                        process(window);
                    } else if (ending && windows.isEmpty()) {
                        var tail = finalWindow();
                        if (tail != null) process(tail);
                        if (limitReached) outcome = new Outcome("AUDIO_LIMIT_EXCEEDED", false);
                        break;
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.interrupted();
            } catch (RuntimeException | java.io.IOException failure) {
                if (!cancelled) outcome = new Outcome("WHISPER_UNAVAILABLE", true);
            } finally {
                synchronized (this) { ending = true; }
                try {
                    meetings.closeStreamSession(sessionId, outcome.reason());
                } finally {
                    active.remove(meetingId, this);
                    finished.complete(outcome);
                }
            }
        }

        private void process(Window window) throws java.io.IOException, InterruptedException {
            var clips = client.transcribeLive(window.pcm());
            if (clips.isEmpty() || cancelled) return;
            var inputs = new ArrayList<FinalSegmentInput>(clips.size());
            for (var clip : clips) {
                long next = ++sequence;
                inputs.add(new FinalSegmentInput("whisper:" + sessionId + ":" + next, next,
                        window.startMs() + clip.startMs(), window.startMs() + clip.endMs(),
                        clip.text(), null, "UNKNOWN"));
            }
            for (var saved : meetings.insertFinalBatchIfAbsent(meetingId, inputs)) {
                if (cancelled) break;
                events.accept(new SpeechEvent("transcript.final", meetingId, sessionId, saved.eventId(), saved.sequence(),
                        saved.startMs(), saved.endMs(), saved.displayText(), saved.speakerLabel(), saved.speakerStatus(),
                        saved.id(), saved.revision(), saved.originalText(), saved.displayText(), saved.transcriptStatus()));
            }
        }
    }

    private record Window(long startMs, byte[] pcm) {}
}
