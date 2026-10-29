package dev.aimeeting.api.provider.openai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aimeeting.api.correction.CorrectionService;
import dev.aimeeting.api.correction.CorrectionSuggestion;
import dev.aimeeting.api.provider.SummaryProvider;
import dev.aimeeting.api.summary.MeetingSummary;
import dev.aimeeting.api.summary.SummaryService;
import dev.aimeeting.api.topic.GlossaryTerm;
import dev.aimeeting.api.topic.TopicService;
import dev.aimeeting.api.usage.ledger.CostLedgerService;
import dev.aimeeting.api.usage.ledger.ReservationRequest;
import dev.aimeeting.api.usage.ledger.Settlement;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/** Offline-testable OpenAI workflow; the Spring application service owns paid-mode activation. */
public final class OpenAiMeetingOrchestrator {
    private static final String PROVIDER = "openai";
    private static final String MODEL = "gpt-6.1-sol";

    @FunctionalInterface
    public interface Transport {
        CompletableFuture<OpenAiPocClient.Response> request(ObjectNode payload);
    }

    private final CostLedgerService ledger;
    private final SummaryService summaries;
    private final CorrectionService corrections;
    private final TopicService topics;
    private final OpenAiPocLimits limits;
    private final String priceVersion;
    private final Transport transport;
    private final BigDecimal maximumMeetingUsd;
    private final long maximumMeetingSeconds;

    public OpenAiMeetingOrchestrator(CostLedgerService ledger, SummaryService summaries,
                                     CorrectionService corrections, OpenAiPocLimits limits,
                                     String priceVersion, Transport transport) {
        this(ledger, summaries, corrections, null, limits, priceVersion, new BigDecimal("100.00"),
                Integer.MAX_VALUE, transport);
    }

    public OpenAiMeetingOrchestrator(CostLedgerService ledger, SummaryService summaries,
                                     CorrectionService corrections, TopicService topics, OpenAiPocLimits limits,
                                     String priceVersion, BigDecimal maximumMeetingUsd, long maximumMeetingSeconds,
                                     Transport transport) {
        this.ledger = Objects.requireNonNull(ledger);
        this.summaries = Objects.requireNonNull(summaries);
        this.corrections = Objects.requireNonNull(corrections);
        this.topics = topics;
        this.limits = Objects.requireNonNull(limits);
        this.priceVersion = requirePriceVersion(priceVersion);
        this.maximumMeetingUsd = Objects.requireNonNull(maximumMeetingUsd);
        if (maximumMeetingSeconds < 1) throw new IllegalArgumentException("Invalid maximum meeting duration");
        this.maximumMeetingSeconds = maximumMeetingSeconds;
        this.transport = Objects.requireNonNull(transport);
    }

    public CompletableFuture<MeetingSummary> generateSummary(UUID requestId, UUID meetingId) {
        SummaryService.AiSnapshot snapshot = summaries.prepareAiGeneration(meetingId);
        List<List<dev.aimeeting.api.meeting.TranscriptSegment>> chunks =
                MeetingAiPayloads.partitionTranscript(snapshot.transcript());
        AtomicInteger sequence = new AtomicInteger();
        CompletableFuture<List<SummaryProvider.Summary>> partials = requestSummary(requestId,
                sequence.getAndIncrement(), meetingId, "SUMMARY_CHUNK", chunks.getFirst(), chunks.getFirst())
                .thenApply(List::of);
        for (List<dev.aimeeting.api.meeting.TranscriptSegment> chunk : chunks.subList(1, chunks.size())) {
            partials = partials.thenCompose(current -> requestSummary(requestId, sequence.getAndIncrement(), meetingId,
                    "SUMMARY_CHUNK", chunk, chunk).thenApply(summary -> append(current, summary)));
        }
        return partials.thenCompose(current -> reduceSummaries(requestId, sequence, meetingId,
                        snapshot.transcript(), current))
                .thenApply(summary -> summaries.storeAiGenerated(snapshot, summary));
    }

    public CompletableFuture<List<CorrectionSuggestion>> generateCorrections(UUID requestId, UUID meetingId) {
        CorrectionService.AiSnapshot snapshot = corrections.prepareAiGeneration(meetingId);
        AtomicInteger sequence = new AtomicInteger();
        List<List<dev.aimeeting.api.meeting.TranscriptSegment>> chunks =
                MeetingAiPayloads.partitionTranscript(snapshot.transcript());
        CompletableFuture<List<MeetingAiPayloads.CorrectionPatch>> patches =
                requestCorrections(requestId, sequence.getAndIncrement(), meetingId, snapshot, chunks.getFirst());
        for (List<dev.aimeeting.api.meeting.TranscriptSegment> chunk : chunks.subList(1, chunks.size())) {
            patches = patches.thenCompose(current -> {
                return requestCorrections(requestId, sequence.getAndIncrement(), meetingId, snapshot, chunk)
                        .thenApply(next -> appendAll(current, next));
            });
        }
        return patches.thenApply(result -> corrections.storeAiGenerated(snapshot, result));
    }

    public CompletableFuture<List<GlossaryTerm>> generateTopicCandidates(UUID requestId, UUID topicId) {
        if (topics == null) throw new IllegalStateException("Topic AI is not configured");
        TopicService.AiSnapshot snapshot = topics.prepareAiSuggestions(topicId);
        ObjectNode payload = MeetingAiPayloads.topicCandidatesRequest(snapshot.topic(), snapshot.existingTerms(),
                limits.maxOutputTokens());
        OpenAiPocClient.validateRequest(payload, limits);
        ledger.reserve(requestId, new ReservationRequest(null, PROVIDER, MODEL, "GLOSSARY", priceVersion,
                limits.maximumEstimatedUsd(), 1, topicId));
        ledger.startDispatch(requestId);
        return dispatch(requestId, payload).thenApply(response -> {
            requireCompleted(response);
            return topics.storeAiSuggestions(snapshot,
                    MeetingAiPayloads.parseTopicCandidates(response.outputJson(), snapshot.existingTerms()));
        });
    }

    private CompletableFuture<SummaryProvider.Summary> requestSummary(UUID rootRequestId, int sequence,
            UUID meetingId, String operation, List<dev.aimeeting.api.meeting.TranscriptSegment> requestTranscript,
            List<dev.aimeeting.api.meeting.TranscriptSegment> evidenceTranscript) {
        ObjectNode payload = MeetingAiPayloads.summaryRequest(requestTranscript, limits.maxOutputTokens());
        UUID child = childRequestId(rootRequestId, sequence);
        preflightAndClaim(child, meetingId, operation, audioSeconds(requestTranscript), payload);
        return dispatch(child, payload).thenApply(response -> {
            requireCompleted(response);
            return MeetingAiPayloads.parseSummary(response.outputJson(), evidenceTranscript);
        });
    }

    private CompletableFuture<List<MeetingAiPayloads.CorrectionPatch>> requestCorrections(UUID rootRequestId,
            int sequence, UUID meetingId, CorrectionService.AiSnapshot snapshot,
            List<dev.aimeeting.api.meeting.TranscriptSegment> transcript) {
        ObjectNode payload = MeetingAiPayloads.correctionRequest(transcript, snapshot.aliases(),
                limits.maxOutputTokens());
        UUID child = childRequestId(rootRequestId, sequence);
        preflightAndClaim(child, meetingId, "CORRECTION_CHUNK", audioSeconds(transcript), payload);
        return dispatch(child, payload).thenApply(response -> {
            requireCompleted(response);
            return MeetingAiPayloads.parseCorrections(response.outputJson(), transcript, snapshot.aliases());
        });
    }

    private CompletableFuture<SummaryProvider.Summary> reduceSummaries(UUID rootRequestId, AtomicInteger sequence,
            UUID meetingId, List<dev.aimeeting.api.meeting.TranscriptSegment> evidence,
            List<SummaryProvider.Summary> initial) {
        if (initial.size() == 1) return CompletableFuture.completedFuture(initial.getFirst());
        List<List<SummaryProvider.Summary>> pairs = new ArrayList<>();
        for (int index = 0; index < initial.size(); index += 2) {
            pairs.add(initial.subList(index, Math.min(index + 2, initial.size())));
        }
        CompletableFuture<List<SummaryProvider.Summary>> next = CompletableFuture.completedFuture(List.of());
        for (List<SummaryProvider.Summary> pair : pairs) {
            if (pair.size() == 1) {
                next = next.thenApply(current -> append(current, pair.getFirst()));
                continue;
            }
            next = next.thenCompose(current -> {
                ObjectNode payload = MeetingAiPayloads.summarySynthesisRequest(pair, limits.maxOutputTokens());
                UUID child = childRequestId(rootRequestId, sequence.getAndIncrement());
                preflightAndClaim(child, meetingId, "SUMMARY_REDUCE", audioSeconds(evidence), payload);
                return dispatch(child, payload).thenApply(response -> {
                    requireCompleted(response);
                    return append(current, MeetingAiPayloads.parseSummary(response.outputJson(), evidence));
                });
            });
        }
        return next.thenCompose(reduced -> reduceSummaries(rootRequestId, sequence, meetingId, evidence, reduced));
    }

    private void preflightAndClaim(UUID requestId, UUID meetingId, String operation, long audioSeconds,
                                   ObjectNode payload) {
        OpenAiPocClient.validateRequest(payload, limits);
        claim(requestId, meetingId, operation, audioSeconds);
    }

    private void claim(UUID requestId, UUID meetingId, String operation, long audioSeconds) {
        Objects.requireNonNull(requestId, "requestId");
        ledger.reserveBounded(requestId, new ReservationRequest(meetingId, PROVIDER, MODEL, operation, priceVersion,
                limits.maximumEstimatedUsd(), 1), maximumMeetingUsd, maximumMeetingSeconds);
        ledger.startDispatch(requestId);
    }

    private static UUID childRequestId(UUID root, int sequence) {
        Objects.requireNonNull(root, "requestId");
        if (sequence == 0) return root;
        return UUID.nameUUIDFromBytes((root + ":" + sequence).getBytes(StandardCharsets.UTF_8));
    }

    private static <T> List<T> append(List<T> source, T value) {
        ArrayList<T> result = new ArrayList<>(source);
        result.add(value);
        return List.copyOf(result);
    }

    private static <T> List<T> appendAll(List<T> source, List<T> values) {
        ArrayList<T> result = new ArrayList<>(source);
        result.addAll(values);
        return List.copyOf(result);
    }

    private CompletableFuture<OpenAiPocClient.Response> dispatch(UUID requestId, ObjectNode payload) {
        CompletableFuture<OpenAiPocClient.Response> exchange;
        try {
            exchange = transport.request(payload);
            if (exchange == null) throw new IllegalStateException("OpenAI transport returned no future");
        } catch (RuntimeException failure) {
            ledger.markUncertain(requestId);
            return CompletableFuture.failedFuture(new IllegalStateException("OpenAI dispatch outcome is uncertain"));
        }
        CompletableFuture<OpenAiPocClient.Response> bounded = exchange.copy()
                .orTimeout(limits.timeout().toMillis(), TimeUnit.MILLISECONDS);
        return bounded.handle((response, failure) -> {
            if (failure != null || response == null || response.usage() == null) {
                ledger.markUncertain(requestId);
                throw new CompletionException(new IllegalStateException("OpenAI dispatch outcome is uncertain"));
            }
            OpenAiPocClient.TokenUsage usage = response.usage();
            if (!validUsage(usage)) {
                ledger.markUncertain(requestId);
                throw new CompletionException(new IllegalStateException("OpenAI usage was invalid"));
            }
            BigDecimal estimated = limits.estimatedUsd(usage.inputTokens(), usage.outputTokens());
            ledger.settle(requestId, new Settlement(estimated, null, usage.inputTokens(), usage.outputTokens()));
            if (usage.inputTokens() > limits.maxInputTokens() || usage.outputTokens() > limits.maxOutputTokens()) {
                throw new CompletionException(new IllegalStateException("OpenAI usage exceeded the request reservation"));
            }
            return response;
        });
    }

    private static boolean validUsage(OpenAiPocClient.TokenUsage usage) {
        if (usage.inputTokens() < 0 || usage.outputTokens() < 0 || usage.totalTokens() < 0) return false;
        try {
            return Math.addExact(usage.inputTokens(), usage.outputTokens()) == usage.totalTokens();
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private static void requireCompleted(OpenAiPocClient.Response response) {
        if (response.status() != OpenAiPocClient.Status.COMPLETED) {
            throw new IllegalStateException("OpenAI request did not complete: " + response.status());
        }
    }

    private static long audioSeconds(List<dev.aimeeting.api.meeting.TranscriptSegment> transcript) {
        long maximumEndMs = transcript.stream().mapToLong(dev.aimeeting.api.meeting.TranscriptSegment::endMs).max().orElse(1L);
        return Math.max(1L, Math.floorDiv(Math.addExact(maximumEndMs, 999L), 1_000L));
    }

    private static String requirePriceVersion(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new IllegalArgumentException("Invalid OpenAI price version");
        }
        return normalized;
    }
}
