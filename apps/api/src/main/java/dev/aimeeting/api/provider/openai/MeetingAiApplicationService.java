package dev.aimeeting.api.provider.openai;

import dev.aimeeting.api.config.AiConfiguration;
import dev.aimeeting.api.correction.CorrectionService;
import dev.aimeeting.api.correction.CorrectionSuggestion;
import dev.aimeeting.api.summary.MeetingSummary;
import dev.aimeeting.api.summary.SummaryService;
import dev.aimeeting.api.topic.GlossaryTerm;
import dev.aimeeting.api.topic.TopicService;
import dev.aimeeting.api.usage.ledger.CostLedgerService;
import dev.aimeeting.api.web.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Activates the real OpenAI path only for the explicit paid server mode. */
@Service
public class MeetingAiApplicationService {
    private static final int MAX_INPUT_TOKENS = 200_000;
    private final AiConfiguration configuration;
    private final Supplier<OpenAiMeetingOrchestrator> orchestrator;
    private final Semaphore concurrency = new Semaphore(1);

    @Autowired
    public MeetingAiApplicationService(AiConfiguration configuration, CostLedgerService ledger,
                                       SummaryService summaries, CorrectionService corrections,
                                       TopicService topics) {
        this(configuration, ledger, summaries, corrections, topics, null);
    }

    MeetingAiApplicationService(AiConfiguration configuration, CostLedgerService ledger,
                                SummaryService summaries, CorrectionService corrections,
                                TopicService topics, OpenAiMeetingOrchestrator.Transport testTransport) {
        this.configuration = configuration;
        this.orchestrator = () -> {
        OpenAiPocLimits limits = new OpenAiPocLimits(Duration.ofSeconds(configuration.timeoutSeconds()),
                MAX_INPUT_TOKENS, configuration.outputTokenCap(), configuration.openAiInputRate(),
                configuration.openAiOutputRate(), configuration.maximumMeetingUsd());
        OpenAiMeetingOrchestrator.Transport transport = testTransport == null
                ? payload -> OpenAiPocClient.request(configuration.enabled(), configuration.openAiApiKey(),
                        payload, limits)
                : testTransport;
        return new OpenAiMeetingOrchestrator(ledger, summaries, corrections, topics, limits,
                configuration.priceVersion(), configuration.maximumMeetingUsd(),
                configuration.maximumAudioSeconds(), transport);
        };
    }

    public boolean enabled() { return configuration.enabled(); }

    public CompletableFuture<MeetingSummary> generateSummary(UUID meetingId) {
        return bounded(() -> orchestrator.get().generateSummary(UUID.randomUUID(), meetingId));
    }

    public CompletableFuture<List<CorrectionSuggestion>> generateCorrections(UUID meetingId) {
        return bounded(() -> orchestrator.get().generateCorrections(UUID.randomUUID(), meetingId));
    }

    public CompletableFuture<List<GlossaryTerm>> generateTopicCandidates(UUID topicId) {
        return bounded(() -> orchestrator.get().generateTopicCandidates(UUID.randomUUID(), topicId));
    }

    private <T> CompletableFuture<T> bounded(Supplier<CompletableFuture<T>> operation) {
        if (!configuration.enabled()) {
            return CompletableFuture.failedFuture(new ApiException(HttpStatus.CONFLICT, "PAID_API_DISABLED",
                    "Paid AI mode is disabled"));
        }
        configuration.requireLlm();
        if (!concurrency.tryAcquire()) {
            return CompletableFuture.failedFuture(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_BUSY",
                    "Another AI request is already running"));
        }
        try {
            CompletableFuture<T> source = operation.get();
            if (source == null) throw new IllegalStateException("AI operation returned no future");
            CompletableFuture<T> exposed = new CompletableFuture<>();
            source.whenComplete((value, failure) -> {
                concurrency.release();
                if (failure == null) exposed.complete(value);
                else exposed.completeExceptionally(failure);
            });
            return exposed;
        } catch (RuntimeException failure) {
            concurrency.release();
            return CompletableFuture.failedFuture(failure);
        }
    }
}
