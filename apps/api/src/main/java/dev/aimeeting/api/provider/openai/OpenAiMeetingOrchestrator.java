package dev.aimeeting.api.provider.openai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aimeeting.api.correction.CorrectionService;
import dev.aimeeting.api.correction.CorrectionSuggestion;
import dev.aimeeting.api.provider.SummaryProvider;
import dev.aimeeting.api.summary.MeetingSummary;
import dev.aimeeting.api.summary.SummaryService;
import dev.aimeeting.api.usage.ledger.CostLedgerService;
import dev.aimeeting.api.usage.ledger.ReservationRequest;
import dev.aimeeting.api.usage.ledger.Settlement;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Offline-testable application boundary for the OpenAI POC. This is deliberately not a Spring bean or endpoint;
 * production activation remains gated on the paid POC decision.
 */
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
    private final OpenAiPocLimits limits;
    private final String priceVersion;
    private final Transport transport;

    public OpenAiMeetingOrchestrator(CostLedgerService ledger, SummaryService summaries,
                                     CorrectionService corrections, OpenAiPocLimits limits,
                                     String priceVersion, Transport transport) {
        this.ledger = Objects.requireNonNull(ledger);
        this.summaries = Objects.requireNonNull(summaries);
        this.corrections = Objects.requireNonNull(corrections);
        this.limits = Objects.requireNonNull(limits);
        this.priceVersion = requirePriceVersion(priceVersion);
        this.transport = Objects.requireNonNull(transport);
    }

    public CompletableFuture<MeetingSummary> generateSummary(UUID requestId, UUID meetingId) {
        SummaryService.AiSnapshot snapshot = summaries.prepareAiGeneration(meetingId);
        ObjectNode payload = MeetingAiPayloads.summaryRequest(snapshot.transcript(), limits.maxOutputTokens());
        OpenAiPocClient.validateRequest(payload, limits);
        claim(requestId, meetingId, "SUMMARY", audioSeconds(snapshot.transcript()));
        return dispatch(requestId, payload).thenApply(response -> {
            requireCompleted(response);
            SummaryProvider.Summary parsed = MeetingAiPayloads.parseSummary(response.outputJson(), snapshot.transcript());
            return summaries.storeAiGenerated(snapshot, parsed);
        });
    }

    public CompletableFuture<List<CorrectionSuggestion>> generateCorrections(UUID requestId, UUID meetingId) {
        CorrectionService.AiSnapshot snapshot = corrections.prepareAiGeneration(meetingId);
        ObjectNode payload = MeetingAiPayloads.correctionRequest(
                snapshot.transcript(), snapshot.aliases(), limits.maxOutputTokens());
        OpenAiPocClient.validateRequest(payload, limits);
        claim(requestId, meetingId, "CORRECTION", audioSeconds(snapshot.transcript()));
        return dispatch(requestId, payload).thenApply(response -> {
            requireCompleted(response);
            List<MeetingAiPayloads.CorrectionPatch> parsed = MeetingAiPayloads.parseCorrections(
                    response.outputJson(), snapshot.transcript(), snapshot.aliases());
            return corrections.storeAiGenerated(snapshot, parsed);
        });
    }

    private void claim(UUID requestId, UUID meetingId, String operation, long audioSeconds) {
        Objects.requireNonNull(requestId, "requestId");
        ledger.reserve(requestId, new ReservationRequest(meetingId, PROVIDER, MODEL, operation, priceVersion,
                limits.maximumEstimatedUsd(), audioSeconds));
        ledger.startDispatch(requestId);
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
