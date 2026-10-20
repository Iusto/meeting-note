package dev.aimeeting.api.provider.deepgram;

import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.provider.SpeechEvent;
import dev.aimeeting.api.topic.TopicRepository;
import dev.aimeeting.api.usage.ledger.CostLedgerService;
import dev.aimeeting.api.usage.ledger.ReservationRequest;
import dev.aimeeting.api.usage.ledger.Settlement;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Application integration rehearsal, restricted to a literal loopback WebSocket server.
 * Not a Spring bean: production activation still requires the real-audio quality gate.
 * Input is a new, continuous signed little-endian PCM16, 16 kHz, mono stream per session.
 */
public final class DeepgramMeetingPoc implements AutoCloseable {
    private static final long PCM_BYTES_PER_SECOND = 32_000;
    private static final int MAX_FRAME_BYTES = 65_536;
    private final UUID requestId;
    private final UUID meetingId;
    private final UUID sessionId;
    private final MeetingRepository meetings;
    private final CostLedgerService ledger;
    private final PocLimits limits;
    private final Consumer<SpeechEvent> events;
    private final DeepgramTranscriptBridge transcript;
    private final ThreadPoolExecutor persistence;
    private final AtomicBoolean failed = new AtomicBoolean();
    private final CompletableFuture<DeepgramPocSession.StopReason> completion = new CompletableFuture<>();
    private final long maximumBytes;
    private volatile DeepgramPocSession transport;
    private long submittedBytes;
    private boolean closing;

    private DeepgramMeetingPoc(UUID requestId, UUID meetingId, UUID sessionId, long audioOffsetMs,
                              MeetingRepository meetings, CostLedgerService ledger, PocLimits limits,
                              Consumer<SpeechEvent> events) {
        this.requestId = requestId;
        this.meetingId = meetingId;
        this.sessionId = sessionId;
        this.meetings = meetings;
        this.ledger = ledger;
        this.limits = limits;
        this.events = events;
        this.maximumBytes = limits.maxDuration().getSeconds() * PCM_BYTES_PER_SECOND
                + limits.maxDuration().getNano() * PCM_BYTES_PER_SECOND / 1_000_000_000L;
        this.persistence = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128), runnable -> {
                    Thread thread = new Thread(runnable, "transcript-poc-persistence");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.transcript = new DeepgramTranscriptBridge(meetingId, sessionId, audioOffsetMs, meetings, events);
    }

    public static CompletableFuture<DeepgramMeetingPoc> openLoopback(
            URI endpoint, UUID requestId, UUID meetingId, long audioOffsetMs, boolean diarization,
            PocLimits limits, String priceVersion, MeetingRepository meetings, TopicRepository topics,
            CostLedgerService ledger, Consumer<SpeechEvent> events) {
        // Reject remote endpoints before any ledger mutation or network operation.
        DeepgramPocSession.validateLoopbackEndpoint(endpoint);
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(events);
        if (audioOffsetMs < 0) throw new IllegalArgumentException("Invalid audio offset");
        var meeting = meetings.find(meetingId);
        if (!"STARTED".equals(meeting.status())) throw new IllegalStateException("Meeting must be started first");
        List<String> keyterms = meeting.topicId() == null ? List.of() : topics.approvedKeyterms(meeting.topicId());
        DeepgramOptions options = new DeepgramOptions(diarization, keyterms, DeepgramAudioFormat.pcm16kMono());
        long maxSeconds = limits.maxDuration().getSeconds() + (limits.maxDuration().getNano() == 0 ? 0 : 1);
        ledger.reserve(requestId, new ReservationRequest(meetingId, "DEEPGRAM_POC", "nova-3", "TRANSCRIPTION",
                priceVersion, limits.maximumEstimatedUsd(), maxSeconds));
        // One-shot dispatch also prevents duplicate request IDs opening a second connection.
        ledger.startDispatch(requestId);
        UUID sessionId;
        DeepgramMeetingPoc poc;
        try {
            sessionId = meetings.createStreamSession(meetingId, "DEEPGRAM_POC", audioOffsetMs);
            poc = new DeepgramMeetingPoc(requestId, meetingId, sessionId, audioOffsetMs, meetings, ledger, limits, events);
        } catch (RuntimeException failure) {
            ledger.markUncertain(requestId);
            return CompletableFuture.failedFuture(new IllegalStateException("Transcription preparation failed"));
        }
        CompletableFuture<DeepgramMeetingPoc> ready = new CompletableFuture<>();
        var connecting = DeepgramPocSession.connectLoopback(endpoint, options, limits, poc::receive);
        connecting.whenComplete((transport, failure) -> {
            if (failure != null) {
                poc.failed.set(true);
                poc.finishPersistence(DeepgramPocSession.StopReason.CONNECT_FAILED);
                ready.completeExceptionally(new IllegalStateException("Transcription connection failed"));
                return;
            }
            poc.transport = transport;
            transport.completion().thenAccept(poc::finishPersistence);
            if (poc.failed.get() || ready.isCancelled()) transport.close();
            if (!ready.complete(poc)) transport.close();
        });
        ready.whenComplete((value, failure) -> {
            if (ready.isCancelled()) { connecting.cancel(true); poc.close(); }
        });
        return ready;
    }

    private void receive(DeepgramMessage message) {
        try {
            persistence.execute(() -> {
                try {
                    if (failed.get()) return;
                    if (message instanceof DeepgramMessage.Results result) transcript.accept(result);
                    else if (message instanceof DeepgramMessage.Error) events.accept(status("stream.warning", "PROVIDER_WARNING"));
                } catch (RuntimeException failure) {
                    failed.set(true);
                    DeepgramPocSession current = transport;
                    if (current != null) current.close();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            failed.set(true);
            throw new IllegalStateException("Transcription persistence queue is full");
        }
    }

    public synchronized CompletableFuture<Void> sendAudio(byte[] pcm) {
        Objects.requireNonNull(pcm);
        if (pcm.length == 0 || pcm.length % 2 != 0 || pcm.length > MAX_FRAME_BYTES) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid PCM16 audio frame"));
        }
        if (closing || transport == null || failed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Transcription session is closed"));
        }
        if (pcm.length > maximumBytes - submittedBytes) {
            close();
            return CompletableFuture.failedFuture(new IllegalStateException("Transcription audio duration limit exceeded"));
        }
        submittedBytes += pcm.length;
        return transport.sendAudio(pcm);
    }

    public synchronized CompletableFuture<DeepgramPocSession.StopReason> finish() {
        closing = true;
        if (transport != null) transport.finish();
        return completion.copy();
    }

    public CompletableFuture<DeepgramPocSession.StopReason> completion() { return completion.copy(); }
    public UUID streamSessionId() { return sessionId; }

    @Override public synchronized void close() {
        closing = true;
        DeepgramPocSession current = transport;
        if (current != null) current.close();
    }

    private void finishPersistence(DeepgramPocSession.StopReason reason) {
        synchronized (this) { closing = true; }
        persistence.shutdown();
        Thread.startVirtualThread(() -> {
            try {
                if (!persistence.awaitTermination(5, TimeUnit.SECONDS)) {
                    failed.set(true);
                    persistence.shutdownNow();
                }
                if (!persistence.isTerminated()) {
                    ledger.markUncertain(requestId);
                    meetings.closeStreamSession(sessionId, "PERSISTENCE_TIMEOUT");
                    completion.completeExceptionally(new IllegalStateException("Transcription persistence timed out"));
                    return;
                }
                transcript.close();
                if (reason == DeepgramPocSession.StopReason.COMPLETED && !failed.get()) {
                    long bytes;
                    synchronized (this) { bytes = submittedBytes; }
                    BigDecimal estimate = limits.estimatedUsdPerMinute().multiply(BigDecimal.valueOf(bytes))
                            .divide(BigDecimal.valueOf(PCM_BYTES_PER_SECOND * 60), 6, RoundingMode.UP);
                    ledger.settle(requestId, new Settlement(estimate,
                            (bytes + PCM_BYTES_PER_SECOND - 1) / PCM_BYTES_PER_SECOND, null, null));
                } else ledger.markUncertain(requestId);
                meetings.closeStreamSession(sessionId, failed.get() ? "PERSISTENCE_FAILED" : reason.name());
                // A provider failure never stops the independent recording/meeting path.
                if (failed.get()) completion.completeExceptionally(new IllegalStateException("Transcription persistence failed"));
                else completion.complete(reason);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                completion.completeExceptionally(new IllegalStateException("Transcription cleanup interrupted"));
            } catch (RuntimeException failure) {
                completion.completeExceptionally(new IllegalStateException("Transcription cleanup failed"));
            }
        });
    }

    private SpeechEvent status(String type, String status) {
        return new SpeechEvent(type, meetingId, sessionId, null, null, null, null, null, null, null,
                null, null, null, null, status);
    }
}
