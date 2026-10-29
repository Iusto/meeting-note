package dev.aimeeting.api.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aimeeting.api.config.AiConfiguration;
import dev.aimeeting.api.config.AppProperties;
import dev.aimeeting.api.correction.CorrectionService;
import dev.aimeeting.api.meeting.InputSource;
import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.meeting.TranscriptSegment;
import dev.aimeeting.api.summary.SummaryService;
import dev.aimeeting.api.summary.MeetingSummary;
import dev.aimeeting.api.provider.SummaryProvider;
import dev.aimeeting.api.topic.GlossaryTerm;
import dev.aimeeting.api.topic.Topic;
import dev.aimeeting.api.topic.TopicService;
import dev.aimeeting.api.usage.ledger.CostLedgerService;
import dev.aimeeting.api.web.ApiException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:openai-orchestration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class OpenAiMeetingOrchestratorIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MeetingRepository meetings;
    @Autowired TopicService topics;
    @Autowired SummaryService summaries;
    @Autowired CorrectionService corrections;
    @Autowired CostLedgerService ledger;

    private final OpenAiPocLimits limits = new OpenAiPocLimits(Duration.ofSeconds(5), 50_000, 100,
            new BigDecimal("1.00"), new BigDecimal("2.00"), new BigDecimal("0.10"));

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM correction_suggestions");
        jdbc.update("DELETE FROM meeting_summaries");
        jdbc.update("DELETE FROM ai_usage_records");
        jdbc.update("DELETE FROM transcript_revisions");
        jdbc.update("DELETE FROM transcript_segments");
        jdbc.update("DELETE FROM stream_sessions");
        jdbc.update("DELETE FROM recordings");
        jdbc.update("UPDATE runtime_state SET active_meeting_id=NULL WHERE id=1");
        jdbc.update("DELETE FROM meetings");
        jdbc.update("DELETE FROM topic_terms");
        jdbc.update("DELETE FROM glossary_aliases");
        jdbc.update("DELETE FROM glossary_terms");
        jdbc.update("DELETE FROM meeting_topics");
        jdbc.update("UPDATE app_settings SET monthly_budget_usd=10,per_meeting_max_minutes=240 WHERE id=1");
    }

    @Test
    void storesSummaryAndSettlesMeasuredUsageWithoutCallingANetworkClient() {
        Fixture fixture = fixture("summary alias");
        String output = summaryJson(fixture.segment().id());
        OpenAiMeetingOrchestrator orchestrator = orchestrator(payload ->
                CompletableFuture.completedFuture(completed(output, 120, 30)));
        UUID requestId = UUID.randomUUID();

        var result = orchestrator.generateSummary(requestId, fixture.meeting().id()).join();

        assertThat(result.provider()).isEqualTo("OPENAI");
        assertThat(result.content().summary()).isEqualTo("Synthetic summary");
        assertThat(jdbc.queryForObject("SELECT state FROM ai_usage_records WHERE id=?", String.class, requestId))
                .isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT input_tokens FROM ai_usage_records WHERE id=?", Long.class, requestId))
                .isEqualTo(120L);
    }

    @Test
    void settlesRefusalAndInvalidPayloadButNeverStoresThem() {
        Fixture fixture = fixture("invalid alias");
        UUID refused = UUID.randomUUID();
        OpenAiMeetingOrchestrator refusal = orchestrator(payload -> CompletableFuture.completedFuture(
                new OpenAiPocClient.Response(OpenAiPocClient.Status.REFUSED, "resp-1", null,
                        new OpenAiPocClient.TokenUsage(10, 2, 12))));
        assertThatThrownBy(() -> refusal.generateSummary(refused, fixture.meeting().id()).join())
                .hasRootCauseMessage("OpenAI request did not complete: REFUSED");
        assertSettledWithoutSummary(refused, fixture.meeting().id());

        UUID invalid = UUID.randomUUID();
        OpenAiMeetingOrchestrator malformed = orchestrator(payload ->
                CompletableFuture.completedFuture(completed("{\"summary\":\"missing evidence fields\"}", 9, 1)));
        assertThatThrownBy(() -> malformed.generateSummary(invalid, fixture.meeting().id()).join())
                .hasRootCauseMessage("Invalid OpenAI response");
        assertSettledWithoutSummary(invalid, fixture.meeting().id());
    }

    @Test
    void marksTransportFailureAndMissingUsageUncertainAndDoesNotRetry() {
        Fixture fixture = fixture("uncertain alias");
        AtomicInteger calls = new AtomicInteger();
        UUID failed = UUID.randomUUID();
        OpenAiMeetingOrchestrator timeout = orchestrator(payload -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("synthetic timeout"));
        });
        assertThatThrownBy(() -> timeout.generateSummary(failed, fixture.meeting().id()).join())
                .hasRootCauseMessage("OpenAI dispatch outcome is uncertain");
        assertThat(calls).hasValue(1);
        assertState(failed, "UNCERTAIN");

        UUID missing = UUID.randomUUID();
        OpenAiMeetingOrchestrator noUsage = orchestrator(payload -> CompletableFuture.completedFuture(
                new OpenAiPocClient.Response(OpenAiPocClient.Status.COMPLETED, "resp-2",
                        summaryJson(fixture.segment().id()), null)));
        assertThatThrownBy(() -> noUsage.generateSummary(missing, fixture.meeting().id()).join())
                .hasRootCauseMessage("OpenAI dispatch outcome is uncertain");
        assertState(missing, "UNCERTAIN");
    }

    @Test
    void rejectsDuplicateDispatchAndTranscriptChangesAfterTheCallWhileStillSettlingUsage() {
        Fixture fixture = fixture("race alias");
        AtomicInteger calls = new AtomicInteger();
        UUID duplicate = UUID.randomUUID();
        OpenAiMeetingOrchestrator once = orchestrator(payload -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(completed(summaryJson(fixture.segment().id()), 5, 2));
        });
        once.generateSummary(duplicate, fixture.meeting().id()).join();
        assertThatThrownBy(() -> once.generateSummary(duplicate, fixture.meeting().id()))
                .isInstanceOf(ApiException.class).extracting(error -> ((ApiException) error).code())
                .isEqualTo("DISPATCH_ALREADY_CLAIMED");
        assertThat(calls).hasValue(1);

        UUID raced = UUID.randomUUID();
        OpenAiMeetingOrchestrator changing = orchestrator(payload -> {
            meetings.correct(fixture.meeting().id(), fixture.segment().id(), fixture.segment().revision(),
                    fixture.segment().displayText() + " changed", "USER", "synthetic race", true);
            return CompletableFuture.completedFuture(completed(summaryJson(fixture.segment().id()), 7, 3));
        });
        assertThatThrownBy(() -> changing.generateSummary(raced, fixture.meeting().id()).join())
                .hasRootCauseInstanceOf(ApiException.class)
                .hasRootCauseMessage("Transcript changed while the summary was being generated");
        assertState(raced, "SETTLED");
    }

    @Test
    void storesOnlyValidatedPendingCorrectionsAndRechecksTheAliasAtReviewTime() {
        Fixture fixture = fixture("엠이에스");
        UUID requestId = UUID.randomUUID();
        String output = """
                {"edits":[{"segmentId":"%s","expectedRevision":0,"spanStart":0,
                "originalSpan":"엠이에스","replacement":"MES","reason":"approved glossary","requiresReview":true}]}
                """.formatted(fixture.segment().id());
        OpenAiMeetingOrchestrator orchestrator = orchestrator(payload ->
                CompletableFuture.completedFuture(completed(output, 30, 10)));

        var stored = orchestrator.generateCorrections(requestId, fixture.meeting().id()).join();
        assertThat(stored).singleElement().satisfies(item -> assertThat(item.state()).isEqualTo("PENDING"));
        topics.updateTerm(fixture.topic().id(), fixture.term().id(), null, null, List.of("새별칭"), null);
        assertThatThrownBy(() -> corrections.review(fixture.meeting().id(), stored.getFirst().id(), "APPLIED"))
                .isInstanceOf(ApiException.class).extracting(error -> ((ApiException) error).code())
                .isEqualTo("STALE_CORRECTION");
        assertThat(corrections.review(fixture.meeting().id(), stored.getFirst().id(), "REJECTED").state())
                .isEqualTo("REJECTED");
        assertState(requestId, "SETTLED");
    }

    @Test
    void enforcesTimeoutAndKeepsAccountingAfterTheCallerCancels() throws Exception {
        Fixture timeoutFixture = fixture("timeout alias");
        OpenAiPocLimits shortTimeout = new OpenAiPocLimits(Duration.ofMillis(50), 50_000, 100,
                new BigDecimal("1.00"), new BigDecimal("2.00"), new BigDecimal("0.10"));
        UUID timedOut = UUID.randomUUID();
        OpenAiMeetingOrchestrator timeout = orchestrator(shortTimeout, payload -> new CompletableFuture<>());
        assertThatThrownBy(() -> timeout.generateSummary(timedOut, timeoutFixture.meeting().id()).join())
                .hasRootCauseMessage("OpenAI dispatch outcome is uncertain");
        assertState(timedOut, "UNCERTAIN");

        resetDatabase();
        Fixture cancelledFixture = fixture("cancel alias");
        CompletableFuture<OpenAiPocClient.Response> transportResult = new CompletableFuture<>();
        UUID cancelled = UUID.randomUUID();
        OpenAiMeetingOrchestrator cancelledCaller = orchestrator(payload -> transportResult);
        CompletableFuture<?> callerResult = cancelledCaller.generateSummary(cancelled, cancelledFixture.meeting().id());
        assertThat(callerResult.cancel(true)).isTrue();
        transportResult.complete(completed(summaryJson(cancelledFixture.segment().id()), 8, 2));
        assertState(cancelled, "SETTLED");
    }

    @Test
    void blocksDispatchAtZeroBudgetAndRejectsTamperedOrOverReservationUsage() {
        Fixture fixture = fixture("budget alias");
        jdbc.update("UPDATE app_settings SET monthly_budget_usd=0 WHERE id=1");
        AtomicInteger calls = new AtomicInteger();
        OpenAiMeetingOrchestrator disabled = orchestrator(payload -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(completed(summaryJson(fixture.segment().id()), 1, 1));
        });
        assertThatThrownBy(() -> disabled.generateSummary(UUID.randomUUID(), fixture.meeting().id()))
                .isInstanceOf(ApiException.class).extracting(error -> ((ApiException) error).code())
                .isEqualTo("MONTHLY_BUDGET_EXCEEDED");
        assertThat(calls).hasValue(0);

        jdbc.update("UPDATE app_settings SET monthly_budget_usd=10 WHERE id=1");
        UUID tampered = UUID.randomUUID();
        OpenAiMeetingOrchestrator invalidUsage = orchestrator(payload -> CompletableFuture.completedFuture(
                new OpenAiPocClient.Response(OpenAiPocClient.Status.COMPLETED, "bad-usage",
                        summaryJson(fixture.segment().id()), new OpenAiPocClient.TokenUsage(5, -1, 4))));
        assertThatThrownBy(() -> invalidUsage.generateSummary(tampered, fixture.meeting().id()).join())
                .hasRootCauseMessage("OpenAI usage was invalid");
        assertState(tampered, "UNCERTAIN");

        UUID inconsistent = UUID.randomUUID();
        OpenAiMeetingOrchestrator inconsistentUsage = orchestrator(payload -> CompletableFuture.completedFuture(
                new OpenAiPocClient.Response(OpenAiPocClient.Status.COMPLETED, "bad-total",
                        summaryJson(fixture.segment().id()), new OpenAiPocClient.TokenUsage(5, 1, 99))));
        assertThatThrownBy(() -> inconsistentUsage.generateSummary(inconsistent, fixture.meeting().id()).join())
                .hasRootCauseMessage("OpenAI usage was invalid");
        assertState(inconsistent, "UNCERTAIN");

        UUID over = UUID.randomUUID();
        OpenAiMeetingOrchestrator overReservation = orchestrator(payload ->
                CompletableFuture.completedFuture(completed(summaryJson(fixture.segment().id()), 50_001, 2)));
        assertThatThrownBy(() -> overReservation.generateSummary(over, fixture.meeting().id()).join())
                .hasRootCauseMessage("OpenAI usage exceeded the request reservation");
        assertState(over, "SETTLED");
        assertThat(summaries.latestOrNull(fixture.meeting().id())).isNull();
    }

    @Test
    void serviceBoundariesRejectForgedSnapshotsAndForeignMeetingPatches() {
        Fixture fixture = fixture("forged alias");
        SummaryService.AiSnapshot validSummary = summaries.prepareAiGeneration(fixture.meeting().id());
        Meeting foreignMeeting = meetings.create("Foreign meeting", InputSource.MOCK, fixture.topic().id());
        meetings.start(foreignMeeting.id());
        TranscriptSegment foreignSegment = meetings.insertFinal(foreignMeeting.id(), "foreign-event", 1, 0, 1_000,
                "forged alias 배포", "speaker-0");
        meetings.stop(foreignMeeting.id());
        SummaryService.AiSnapshot forgedSummary = new SummaryService.AiSnapshot(validSummary.meetingId(),
                List.of(foreignSegment), validSummary.snapshot());
        SummaryProvider.Summary foreignEvidence = new SummaryProvider.Summary("forged", List.of(foreignSegment.id()),
                List.of(), List.of(), List.of());
        assertThatThrownBy(() -> summaries.storeAiGenerated(forgedSummary, foreignEvidence))
                .isInstanceOf(ApiException.class).extracting(error -> ((ApiException) error).code())
                .isEqualTo("STALE_AI_SNAPSHOT");

        var correctionSnapshot = corrections.prepareAiGeneration(fixture.meeting().id());
        var foreignPatch = new MeetingAiPayloads.CorrectionPatch(foreignSegment.id(), foreignSegment.revision(), 0,
                "forged alias", "MES", "forged", true);
        assertThatThrownBy(() -> corrections.storeAiGenerated(correctionSnapshot, List.of(foreignPatch)))
                .isInstanceOf(ApiException.class).extracting(error -> ((ApiException) error).code())
                .isEqualTo("INVALID_AI_CORRECTION");
        assertThat(corrections.list(fixture.meeting().id())).isEmpty();
    }

    @Test
    void applyingAnAiCorrectionRecordsTheReviewedActor() {
        Fixture fixture = fixture("review alias");
        String output = """
                {"edits":[{"segmentId":"%s","expectedRevision":0,"spanStart":0,
                "originalSpan":"review alias","replacement":"MES","reason":"approved glossary","requiresReview":true}]}
                """.formatted(fixture.segment().id());
        var suggestion = orchestrator(payload -> CompletableFuture.completedFuture(completed(output, 10, 2)))
                .generateCorrections(UUID.randomUUID(), fixture.meeting().id()).join().getFirst();

        assertThat(corrections.review(fixture.meeting().id(), suggestion.id(), "APPLIED").state()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT actor FROM transcript_revisions WHERE segment_id=?", String.class,
                fixture.segment().id())).isEqualTo("AI_REVIEW");
    }

    @Test
    void rejectsAnOversizedRequestBeforeReservingOrDispatching() {
        Fixture fixture = fixture("preflight alias");
        OpenAiPocLimits tooSmall = new OpenAiPocLimits(Duration.ofSeconds(1), 1, 100,
                new BigDecimal("1.00"), new BigDecimal("2.00"), new BigDecimal("0.01"));
        AtomicInteger calls = new AtomicInteger();
        OpenAiMeetingOrchestrator orchestrator = orchestrator(tooSmall, payload -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(completed(summaryJson(fixture.segment().id()), 1, 1));
        });

        assertThatThrownBy(() -> orchestrator.generateSummary(UUID.randomUUID(), fixture.meeting().id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OpenAI request exceeds the input reservation");
        assertThat(calls).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_records", Integer.class)).isZero();
    }

    @Test
    void summarizesEveryChunkThenReducesWithOriginalEvidence() throws Exception {
        Topic topic = topics.create("Long " + UUID.randomUUID(), "offline");
        Meeting meeting = meetings.create("Long meeting", InputSource.MOCK, topic.id());
        meetings.start(meeting.id());
        java.util.ArrayList<TranscriptSegment> transcript = new java.util.ArrayList<>();
        for (int index = 0; index < 101; index++) {
            transcript.add(meetings.insertFinal(meeting.id(), "long-" + index, index + 1L,
                    index * 1_000L, (index + 1L) * 1_000L, "segment " + index + " " + "x".repeat(300),
                    "speaker-0"));
        }
        meetings.stop(meeting.id());
        AtomicInteger calls = new AtomicInteger();
        ObjectMapper json = new ObjectMapper();
        OpenAiMeetingOrchestrator orchestrator = orchestrator(payload -> {
            calls.incrementAndGet();
            String schema = payload.at("/text/format/name").asText();
            if ("meeting_summary_synthesis".equals(schema)) {
                return CompletableFuture.completedFuture(completed(summaryJson(transcript.getFirst().id()), 20, 5));
            }
            String text = payload.at("/input/0/content/0/text").asText();
            try {
                UUID first = UUID.fromString(json.readTree(text.substring(text.indexOf('{')))
                        .at("/segments/0/id").asText());
                return CompletableFuture.completedFuture(completed(summaryJson(first), 20, 5));
            } catch (Exception invalid) {
                return CompletableFuture.failedFuture(invalid);
            }
        });

        MeetingSummary result = orchestrator.generateSummary(UUID.randomUUID(), meeting.id()).join();

        assertThat(calls).hasValue(3);
        assertThat(result.content().sourceSegmentIds()).containsExactly(transcript.getFirst().id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_records WHERE meeting_id=?", Integer.class,
                meeting.id())).isEqualTo(3);
    }

    @Test
    void storesRealTopicCandidatesPendingWithoutInventingAMeetingSubject() {
        Topic topic = topics.create("Candidate " + UUID.randomUUID(), "Manufacturing");
        String output = """
                {"candidates":[{"canonical":"LOT","meaning":"Production trace unit","aliases":["로트"]}]}
                """;
        OpenAiMeetingOrchestrator orchestrator = new OpenAiMeetingOrchestrator(ledger, summaries, corrections,
                topics, limits, "synthetic-v1", new BigDecimal("1.00"), 14_400,
                payload -> CompletableFuture.completedFuture(completed(output, 20, 5)));
        UUID requestId = UUID.randomUUID();

        List<GlossaryTerm> result = orchestrator.generateTopicCandidates(requestId, topic.id()).join();

        assertThat(result).singleElement().satisfies(term -> {
            assertThat(term.status()).isEqualTo("PENDING");
            assertThat(term.source()).isEqualTo("OPENAI");
        });
        assertThat(jdbc.queryForObject("SELECT meeting_id FROM ai_usage_records WHERE id=?", UUID.class, requestId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT topic_id FROM ai_usage_records WHERE id=?", UUID.class, requestId))
                .isEqualTo(topic.id());
    }

    @Test
    void applicationServiceUsesInjectedPaidTransportAndRejectsConcurrentCharge() {
        Fixture fixture = fixture("application alias");
        CompletableFuture<OpenAiPocClient.Response> pending = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        MeetingAiApplicationService application = new MeetingAiApplicationService(paidConfiguration(), ledger,
                summaries, corrections, topics, payload -> {
                    calls.incrementAndGet();
                    return pending;
                });

        CompletableFuture<MeetingSummary> first = application.generateSummary(fixture.meeting().id());
        CompletableFuture<MeetingSummary> concurrent = application.generateSummary(fixture.meeting().id());

        assertThatThrownBy(concurrent::join).hasRootCauseInstanceOf(ApiException.class)
                .hasRootCauseMessage("Another AI request is already running");
        pending.complete(completed(summaryJson(fixture.segment().id()), 20, 5));
        assertThat(first.join().provider()).isEqualTo("OPENAI");
        assertThat(calls).hasValue(1);
    }

    private OpenAiMeetingOrchestrator orchestrator(OpenAiMeetingOrchestrator.Transport transport) {
        return orchestrator(limits, transport);
    }

    private AiConfiguration paidConfiguration() {
        AppProperties properties = new AppProperties("a-valid-owner-token-000000000000", List.of(),
                Path.of("target"), true, 100);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("OPENAI_API_KEY", "offline-test-key")
                .withProperty("DEEPGRAM_API_KEY", "offline-test-key")
                .withProperty("ai.maximum-meeting-usd", "1.00")
                .withProperty("ai.output-token-cap", "100")
                .withProperty("ai.timeout-seconds", "5")
                .withProperty("ai.price-version", "offline-test");
        return new AiConfiguration(properties, environment);
    }

    private OpenAiMeetingOrchestrator orchestrator(OpenAiPocLimits requestLimits,
                                                    OpenAiMeetingOrchestrator.Transport transport) {
        return new OpenAiMeetingOrchestrator(ledger, summaries, corrections, requestLimits, "synthetic-v1", transport);
    }

    private Fixture fixture(String alias) {
        Topic topic = topics.create("Synthetic " + UUID.randomUUID(), "offline");
        GlossaryTerm term = topics.createTerm(topic.id(), "MES", "Synthetic term", List.of(alias),
                "APPROVED", "MANUAL");
        Meeting meeting = meetings.create("Synthetic meeting", InputSource.MOCK, topic.id());
        meetings.start(meeting.id());
        TranscriptSegment segment = meetings.insertFinal(meeting.id(), "event-1", 1, 0, 1_000,
                alias + " 배포", "speaker-0");
        meeting = meetings.stop(meeting.id());
        return new Fixture(topic, term, meeting, segment);
    }

    private OpenAiPocClient.Response completed(String output, long input, long outputTokens) {
        return new OpenAiPocClient.Response(OpenAiPocClient.Status.COMPLETED, "response-id", output,
                new OpenAiPocClient.TokenUsage(input, outputTokens, input + outputTokens));
    }

    private String summaryJson(UUID segmentId) {
        return """
                {"summary":"Synthetic summary","sourceSegmentIds":["%s"],"decisions":[],"actionItems":[],"unresolved":[]}
                """.formatted(segmentId);
    }

    private void assertSettledWithoutSummary(UUID requestId, UUID meetingId) {
        assertState(requestId, "SETTLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meeting_summaries WHERE meeting_id=?", Integer.class, meetingId))
                .isZero();
    }

    private void assertState(UUID requestId, String state) {
        assertThat(jdbc.queryForObject("SELECT state FROM ai_usage_records WHERE id=?", String.class, requestId))
                .isEqualTo(state);
    }

    private record Fixture(Topic topic, GlossaryTerm term, Meeting meeting, TranscriptSegment segment) {}
}
