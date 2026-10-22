package dev.aimeeting.api.meeting;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import dev.aimeeting.api.summary.SummaryRepository;
import dev.aimeeting.api.settings.AppSettings;
import dev.aimeeting.api.settings.SettingsRepository;
import dev.aimeeting.api.usage.MonthlyUsage;
import dev.aimeeting.api.usage.UsageService;
import dev.aimeeting.api.usage.ledger.CostLedgerService;
import dev.aimeeting.api.usage.ledger.ReservationRequest;
import dev.aimeeting.api.usage.ledger.Settlement;
import dev.aimeeting.api.usage.ledger.UsageState;
import dev.aimeeting.api.web.ApiException;
import dev.aimeeting.api.recording.RecordingService;
import dev.aimeeting.api.storage.RecordingStorage;
import dev.aimeeting.api.provider.deepgram.DeepgramStartupRecovery;
import dev.aimeeting.api.provider.deepgram.DeepgramMeetingPoc;
import dev.aimeeting.api.provider.deepgram.PocLimits;
import dev.aimeeting.api.topic.TopicRepository;
import java.net.URI;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "APP_TEST_DATABASE_URL", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(
        listeners = TemporaryPostgresDatabaseCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class PostgresMigrationIntegrationTest {
    private static TemporaryPostgresDatabase isolatedDatabase;

    @Autowired MeetingRepository meetings;
    @Autowired JdbcTemplate jdbc;
    @Autowired SummaryRepository summaries;
    @Autowired SettingsRepository settings;
    @Autowired UsageService usage;
    @Autowired MeetingDeletionService deletions;
    @Autowired RecordingService recordingService;
    @Autowired RecordingStorage recordingStorage;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired CostLedgerService ledger;
    @Autowired DataSource dataSource;
    @Autowired TopicRepository topics;

    @BeforeEach
    void restoreIndependentOperationalState() {
        jdbc.update("UPDATE stream_sessions SET ended_at=COALESCE(ended_at,CURRENT_TIMESTAMP),close_reason=COALESCE(close_reason,'TEST_CLEANUP') WHERE id IN (SELECT stream_session_id FROM active_deepgram_sessions)");
        jdbc.update("DELETE FROM active_deepgram_sessions");
        jdbc.update("UPDATE meetings SET status='STOPPED',ended_at=COALESCE(ended_at,CURRENT_TIMESTAMP) WHERE status='STARTED'");
        jdbc.update("UPDATE runtime_state SET active_meeting_id=NULL WHERE id=1");
        jdbc.update("UPDATE app_settings SET monthly_budget_usd=100.00,per_meeting_max_minutes=240 WHERE id=1");
    }

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        TemporaryPostgresDatabase database = isolatedDatabase();
        registry.add("spring.datasource.url", database::databaseUrl);
        registry.add("spring.datasource.username", () -> env("APP_TEST_DATABASE_USER", "postgres"));
        registry.add("spring.datasource.password", () -> env("APP_TEST_DATABASE_PASSWORD", "postgres"));
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Test
    void productionTestsUseAnOwnedIsolatedDatabase() {
        String currentDatabase = jdbc.queryForObject("SELECT current_database()", String.class);
        assertThat(currentDatabase)
                .startsWith(TemporaryPostgresDatabase.DATABASE_PREFIX)
                .isEqualTo(isolatedDatabase.databaseName())
                .isNotEqualTo(isolatedDatabase.sourceDatabaseName());
    }

    @Test
    void productionMigrationsEnforceImmutableOriginalText() {
        Meeting meeting = meetings.create("PostgreSQL migration test " + UUID.randomUUID());
        TranscriptSegment segment = meetings.insertFinal(meeting.id(), "pg-" + UUID.randomUUID(), 1, 0, 1,
                "immutable", "speaker_0");
        assertThatThrownBy(() -> jdbc.update("UPDATE transcript_segments SET original_text='changed' WHERE id=?", segment.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("original_text is immutable");
    }

    @Test
    void productionFinalBatchRollsBackAllPartsAndPersistsReplayOnlyOnce() {
        Meeting meeting = meetings.create("Synthetic atomic final " + UUID.randomUUID());
        String eventId = "batch-" + UUID.randomUUID();
        var first = new MeetingRepository.FinalSegmentInput(eventId, 1, 0, 100,
                "first ", "batch_speaker_0", "TENTATIVE");
        var invalidSecond = new MeetingRepository.FinalSegmentInput(eventId + ":1", 2, 200, 100,
                "second", "batch_speaker_1", "TENTATIVE");

        assertThatThrownBy(() -> meetings.insertFinalBatchIfAbsent(meeting.id(),
                java.util.List.of(first, invalidSecond)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(meetings.transcript(meeting.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM speakers WHERE meeting_id=?",
                Long.class, meeting.id())).isZero();

        var second = new MeetingRepository.FinalSegmentInput(eventId + ":1", 2, 100, 200,
                "second", "batch_speaker_1", "TENTATIVE");
        var batch = java.util.List.of(first, second);
        assertThat(meetings.insertFinalBatchIfAbsent(meeting.id(), batch)).hasSize(2);
        assertThat(meetings.insertFinalBatchIfAbsent(meeting.id(), batch)).isEmpty();
        assertThat(meetings.transcript(meeting.id())).extracting(TranscriptSegment::originalText)
                .containsExactly("first ", "second");
    }

    @Test
    void productionDatabaseAllowsOnlyOneActiveDeepgramSessionPerMeeting() throws Exception {
        Meeting meeting = meetings.create("Concurrent Deepgram claim " + UUID.randomUUID());
        meetings.start(meeting.id());
        try {
            UUID firstRequest = UUID.randomUUID();
            UUID secondRequest = UUID.randomUUID();
            ledger.reserve(firstRequest, deepgramRequest(meeting.id(), "1"));
            ledger.reserve(secondRequest, deepgramRequest(meeting.id(), "1"));
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<Object> first = executor.submit(() -> claimDeepgramAfter(start, meeting.id(), firstRequest));
                Future<Object> second = executor.submit(() -> claimDeepgramAfter(start, meeting.id(), secondRequest));
                start.countDown();
                Object firstResult = first.get(10, TimeUnit.SECONDS);
                Object secondResult = second.get(10, TimeUnit.SECONDS);

                assertThat(java.util.List.of(firstResult, secondResult).stream().filter(UUID.class::isInstance).count())
                        .isEqualTo(1);
                assertThat(java.util.List.of(firstResult, secondResult).stream().filter(ApiException.class::isInstance).count())
                        .isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_deepgram_sessions WHERE meeting_id=?",
                        Integer.class, meeting.id())).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stream_sessions WHERE meeting_id=? AND provider='DEEPGRAM_POC'",
                        Integer.class, meeting.id())).isEqualTo(1);

                UUID winner = UUID.class.cast(firstResult instanceof UUID ? firstResult : secondResult);
                meetings.closeStreamSession(winner, "SYNTHETIC_COMPLETE");
                ledger.release(firstResult instanceof UUID ? secondRequest : firstRequest);
                ledger.release(firstResult instanceof UUID ? firstRequest : secondRequest);
            }
        } finally {
            meetings.stop(meeting.id());
        }
    }

    @Test
    void productionStartupLockPreventsCompetingRecoveryAndReconcilesAfterHolderCloses() {
        DeepgramStartupRecovery holder = new DeepgramStartupRecovery(dataSource, meetings);
        DeepgramStartupRecovery contender = new DeepgramStartupRecovery(dataSource, meetings);
        DeepgramStartupRecovery successor = new DeepgramStartupRecovery(dataSource, meetings);
        Meeting meeting = null;
        holder.start();
        try {
            meeting = meetings.create("PostgreSQL restart recovery " + UUID.randomUUID());
            meetings.start(meeting.id());
            UUID request = UUID.randomUUID();
            ledger.reserve(request, deepgramRequest(meeting.id(), "1"));
            UUID session = meetings.createDeepgramStreamSession(meeting.id(), 0, request);
            ledger.startDispatch(request);

            assertThatThrownBy(contender::start).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Another application instance");
            assertThat(jdbc.queryForObject("SELECT state FROM ai_usage_records WHERE id=?", String.class, request))
                    .isEqualTo("DISPATCHED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_deepgram_sessions WHERE request_id=?",
                    Integer.class, request)).isEqualTo(1);

            holder.destroy();
            assertThat(successor.start()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT state FROM ai_usage_records WHERE id=?", String.class, request))
                    .isEqualTo("UNCERTAIN");
            assertThat(jdbc.queryForObject("SELECT close_reason FROM stream_sessions WHERE id=?", String.class, session))
                    .isEqualTo("APP_RESTART");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_deepgram_sessions WHERE request_id=?",
                    Integer.class, request)).isZero();
        } finally {
            if (meeting != null) meetings.stop(meeting.id());
            holder.destroy();
            contender.destroy();
            successor.destroy();
        }
    }

    @Test
    void productionStartupLockLossIsStickyAndBlocksDispatchBeforeSuccessorRecovery() throws Exception {
        DeepgramStartupRecovery holder = new DeepgramStartupRecovery(dataSource, meetings);
        DeepgramStartupRecovery successor = new DeepgramStartupRecovery(dataSource, meetings);
        Meeting meeting = null;
        AtomicInteger ownershipLost = new AtomicInteger();
        UUID dispatchedRequest = UUID.randomUUID();
        UUID dispatchedSession = null;
        holder.start();
        try {
            holder.onOwnershipLost(ownershipLost::incrementAndGet);
            meeting = meetings.create("PostgreSQL forced lock loss " + UUID.randomUUID());
            meetings.start(meeting.id());
            ledger.reserve(dispatchedRequest, deepgramRequest(meeting.id(), "1"));
            dispatchedSession = meetings.createDeepgramStreamSession(meeting.id(), 0, dispatchedRequest);
            ledger.startDispatch(dispatchedRequest);

            long lockKey = 0x41494D454554494EL;
            Integer lockBackendPid = jdbc.queryForObject("""
                    SELECT pid FROM pg_locks
                     WHERE locktype='advisory' AND granted AND mode='ExclusiveLock' AND objsubid=1
                       AND database=(SELECT oid FROM pg_database WHERE datname=current_database())
                       AND classid=((?::bigint >> 32) & 4294967295)::oid
                       AND objid=(?::bigint & 4294967295)::oid
                    """, Integer.class, lockKey, lockKey);
            assertThat(lockBackendPid).isNotNull();
            assertThat(jdbc.queryForObject("SELECT pg_terminate_backend(?, 1000)", Boolean.class, lockBackendPid))
                    .isTrue();

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while ((holder.isOwned() || ownershipLost.get() != 1) && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertThat(holder.isOwned()).isFalse();
            assertThat(ownershipLost).hasValue(1);
            assertThatThrownBy(holder::verifyOwnership).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("restart required");
            assertThatThrownBy(holder::start).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot be restarted");
            assertThat(ownershipLost).hasValue(1);

            long usageBefore = jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_records", Long.class);
            long sessionsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM stream_sessions", Long.class);
            UUID rejectedRequest = UUID.randomUUID();
            Meeting startedMeeting = meeting;
            PocLimits limits = new PocLimits(Duration.ofSeconds(1), new BigDecimal("0.01"),
                    new BigDecimal("0.01"));
            assertThatThrownBy(() -> DeepgramMeetingPoc.openLoopback(
                    URI.create("ws://127.0.0.1:1/v1/listen"), rejectedRequest, startedMeeting.id(), 0,
                    false, limits, "synthetic-rates", meetings, topics, ledger, ignored -> {}, holder))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("restart required");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_records", Long.class)).isEqualTo(usageBefore);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stream_sessions", Long.class)).isEqualTo(sessionsBefore);
            assertThat(meetings.find(meeting.id()).status()).isEqualTo("STARTED");

            assertThat(successor.start()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT state FROM ai_usage_records WHERE id=?",
                    String.class, dispatchedRequest)).isEqualTo("UNCERTAIN");
            assertThat(jdbc.queryForObject("SELECT close_reason FROM stream_sessions WHERE id=?",
                    String.class, dispatchedSession)).isEqualTo("APP_RESTART");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_deepgram_sessions WHERE request_id=?",
                    Integer.class, dispatchedRequest)).isZero();
        } finally {
            try {
                if (dispatchedSession != null) meetings.closeStreamSession(dispatchedSession, "TEST_CLEANUP");
                jdbc.update("DELETE FROM ai_usage_records WHERE id=?", dispatchedRequest);
                if (meeting != null) meetings.stop(meeting.id());
            } finally {
                holder.destroy();
                successor.destroy();
            }
        }
    }

    @Test
    void productionSearchEscapesWildcardsAndFindsOriginalText() {
        String marker = UUID.randomUUID().toString();
        Meeting percent = meetings.create("PostgreSQL 100% " + marker);
        Meeting transcript = meetings.create("PostgreSQL transcript " + UUID.randomUUID());
        meetings.insertFinal(transcript.id(), "search-" + UUID.randomUUID(), 1, 0, 1,
                "원문검색 " + marker, null);

        assertThat(meetings.search("% " + marker)).extracting(Meeting::id).containsExactly(percent.id());
        assertThat(meetings.search("원문검색 " + marker)).extracting(Meeting::id).containsExactly(transcript.id());
    }

    @Test
    void productionSettingsPersistWithRevisionAndUsageCountsCompletedRecordingOnly() {
        AppSettings before = settings.get();
        try {
            AppSettings updated = settings.update(new BigDecimal("12.34"), 70, 120, 45, before.revision());
            assertThat(updated.monthlyBudgetUsd()).isEqualByComparingTo("12.34");
            assertThat(updated.recordingRetentionDays()).isEqualTo(45);
            assertThat(updated.revision()).isEqualTo(before.revision() + 1);
            assertThat(settings.get()).isEqualTo(updated);
            assertThatThrownBy(() -> settings.update(BigDecimal.ZERO, 80, 240, null, before.revision()))
                    .isInstanceOf(ApiException.class).hasMessageContaining("stale");

            String marker = UUID.randomUUID().toString();
            Meeting completed = meetings.create("PG usage completed " + marker);
            Meeting unfinished = meetings.create("PG usage unfinished " + marker);
            YearMonth testMonth = YearMonth.of(2200 + Math.floorMod(marker.hashCode(), 7000), 6);
            OffsetDateTime monthTime = testMonth.atDay(15).atTime(12, 0).atOffset(java.time.ZoneOffset.UTC);
            jdbc.update("UPDATE meetings SET created_at=?,started_at=NULL WHERE id IN (?,?)",
                    monthTime, completed.id(), unfinished.id());
            jdbc.update("""
                    INSERT INTO recordings(id,meeting_id,state,path,sha256,bytes,duration_ms,mime_type,chunk_count,completed_at)
                    VALUES (?,?, 'COMPLETED','recording.bin',?,321,61000,'audio/webm',1,?)
                    """, UUID.randomUUID(), completed.id(), "0".repeat(64), monthTime);
            jdbc.update("""
                    INSERT INTO recordings(id,meeting_id,state,path,sha256,bytes,duration_ms,mime_type,chunk_count)
                    VALUES (?,?, 'UPLOADING','recording.bin',?,999,999000,'audio/webm',1)
                    """, UUID.randomUUID(), unfinished.id(), "0".repeat(64));

            MonthlyUsage result = usage.get(testMonth);
            assertThat(result.recordedSeconds()).isEqualTo(61);
            assertThat(result.totalEstimatedUsd()).isEqualByComparingTo("0.00");
            assertThat(result.totalActualUsd()).isEqualByComparingTo("0.00");
            assertThat(result.meetings()).filteredOn(item -> item.meetingId().equals(completed.id()))
                    .singleElement().satisfies(item -> {
                        assertThat(item.recordedSeconds()).isEqualTo(61);
                        assertThat(item.recordingBytes()).isEqualTo(321);
                    });
            assertThat(result.meetings()).filteredOn(item -> item.meetingId().equals(unfinished.id()))
                    .singleElement().satisfies(item -> {
                        assertThat(item.recordedSeconds()).isZero();
                        assertThat(item.recordingBytes()).isZero();
                    });
        } finally {
            jdbc.update("""
                    UPDATE app_settings SET monthly_budget_usd=?,warn_at_percent=?,per_meeting_max_minutes=?,
                           recording_retention_days=?,revision=? WHERE id=1
                    """, before.monthlyBudgetUsd(), before.warnAtPercent(), before.perMeetingMaxMinutes(),
                    before.recordingRetentionDays(), before.revision());
        }
    }

    @Test
    void ledgerSerializesCompetingReservationsAndPreservesCostAfterDeletion() throws Exception {
        AppSettings before = settings.get();
        Meeting meeting = meetings.create("Synthetic PG ledger " + UUID.randomUUID());
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try {
            settings.update(new BigDecimal("10"), 80, 240, null, before.revision());
            ReservationRequest request = costRequest(meeting.id(), "6");
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<Boolean> one = executor.submit(() -> reserveAfter(start, first, request));
                Future<Boolean> two = executor.submit(() -> reserveAfter(start, second, request));
                start.countDown();
                assertThat(java.util.List.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(true, false);
            }
            UUID accepted = jdbc.queryForObject("SELECT id FROM ai_usage_records WHERE meeting_id=?", UUID.class, meeting.id());
            ledger.startDispatch(accepted);
            ledger.markUncertain(accepted);
            assertThat(ledger.totals(YearMonth.now(java.time.ZoneOffset.UTC)).budgetCommittedUsd()).isEqualByComparingTo("6");
            deletions.delete(meeting.id(), meeting.revision());
            ledger.settle(accepted, new Settlement(new BigDecimal("4.123456"), 60L, 10L, 5L));
            assertThat(ledger.reserve(accepted, request).estimatedUsd()).isEqualByComparingTo("4.123456");
            assertThat(ledger.totals(YearMonth.now(java.time.ZoneOffset.UTC)).actualUsd()).isNull();
            ledger.reconcile(accepted, new BigDecimal("4.50"), "synthetic invoice");
            assertThat(ledger.totals(YearMonth.now(java.time.ZoneOffset.UTC)).budgetCommittedUsd()).isEqualByComparingTo("4.50");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_records WHERE meeting_id=?", Long.class, meeting.id()))
                    .isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM ai_usage_records WHERE id IN (?,?)", first, second);
            restoreSettings(before);
        }
    }

    @Test
    void budgetSettingsLockBlocksReservationUntilNewBudgetIsVisible() throws Exception {
        AppSettings before = settings.get();
        Meeting meeting = meetings.create("Synthetic PG budget race " + UUID.randomUUID());
        UUID request = UUID.randomUUID();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempting = new CountDownLatch(1);
        try {
            settings.update(new BigDecimal("10"), 80, 240, null, before.revision());
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> update = executor.submit(() -> transaction.executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT revision FROM app_settings WHERE id=1 FOR UPDATE", Integer.class);
                    locked.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture release timed out");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    jdbc.update("UPDATE app_settings SET monthly_budget_usd=0,revision=revision+1 WHERE id=1");
                }));
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                Future<Boolean> reservation = executor.submit(() -> {
                    attempting.countDown();
                    return reserveAfter(new CountDownLatch(0), request, costRequest(meeting.id(), "6"));
                });
                assertThat(attempting.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> reservation.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                update.get(5, TimeUnit.SECONDS);
                assertThat(reservation.get(5, TimeUnit.SECONDS)).isFalse();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_records WHERE id=?", Long.class, request)).isZero();
            }
        } finally {
            release.countDown();
            jdbc.update("DELETE FROM ai_usage_records WHERE id=?", request);
            restoreSettings(before);
        }
    }

    @Test
    void uncertainChargeCanBeReconciledWithoutInventingUsage() {
        AppSettings before = settings.get();
        Meeting meeting = meetings.create("Synthetic uncertain invoice " + UUID.randomUUID());
        UUID request = UUID.randomUUID();
        try {
            settings.update(new BigDecimal("10"), 80, 240, null, before.revision());
            ledger.reserve(request, costRequest(meeting.id(), "6"));
            ledger.startDispatch(request);
            ledger.markUncertain(request);
            var billed = ledger.reconcile(request, new BigDecimal("8.123456"), "synthetic invoice");
            assertThat(billed.state()).isEqualTo(UsageState.UNCERTAIN);
            assertThat(billed.estimatedUsd()).isNull();
            assertThat(billed.inputTokens()).isNull();
            assertThat(ledger.reconcile(request, new BigDecimal("8.123456"), "synthetic invoice")).isEqualTo(billed);
            assertThat(ledger.totals(YearMonth.now(java.time.ZoneOffset.UTC)).budgetCommittedUsd())
                    .isEqualByComparingTo("8.123456");
            assertThat(ledger.totals(YearMonth.now(java.time.ZoneOffset.UTC)).reservedUsd()).isEqualByComparingTo("6");
            assertThatThrownBy(() -> ledger.reconcile(request, new BigDecimal("7"), "different invoice"))
                    .isInstanceOf(ApiException.class);
            var settled = ledger.settle(request, new Settlement(new BigDecimal("5"), 60L, 10L, 5L));
            assertThat(settled.actualUsd()).isEqualByComparingTo("8.123456");
            assertThat(ledger.totals(YearMonth.now(java.time.ZoneOffset.UTC)).budgetCommittedUsd())
                    .isEqualByComparingTo("8.123456");
        } finally {
            jdbc.update("DELETE FROM ai_usage_records WHERE id=?", request);
            restoreSettings(before);
        }
    }

    @Test
    void ledgerRejectsCallerTransactionBeforeWaitingForItsLocks() {
        Meeting meeting = meetings.create("Synthetic caller lock boundary " + UUID.randomUUID());
        UUID request = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        long started = System.nanoTime();
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM app_settings WHERE id=1 FOR UPDATE", Integer.class);
            jdbc.queryForObject("SELECT id FROM meetings WHERE id=? FOR UPDATE", UUID.class, meeting.id());
            assertThatThrownBy(() -> ledger.reserve(request, costRequest(meeting.id(), "1")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
            assertThatThrownBy(() -> ledger.startDispatch(request)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.release(request)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.markUncertain(request)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.settle(request, new Settlement(BigDecimal.ONE, null, null, null)))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.reconcile(request, BigDecimal.ONE, "synthetic invoice"))
                    .isInstanceOf(IllegalStateException.class);
        });
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_records WHERE id=?", Long.class, request)).isZero();
    }

    private boolean reserveAfter(CountDownLatch start, UUID id, ReservationRequest request) throws InterruptedException {
        start.await();
        try {
            ledger.reserve(id, request);
            return true;
        } catch (ApiException exception) {
            assertThat(exception.code()).isEqualTo("MONTHLY_BUDGET_EXCEEDED");
            return false;
        }
    }

    private Object claimDeepgramAfter(CountDownLatch start, UUID meetingId, UUID requestId) throws InterruptedException {
        start.await();
        try {
            return meetings.createDeepgramStreamSession(meetingId, 0, requestId);
        } catch (ApiException conflict) {
            return conflict;
        }
    }

    private ReservationRequest costRequest(UUID meetingId, String dollars) {
        return new ReservationRequest(meetingId, "synthetic", "fixture", "SUMMARY", "fixture-v1", new BigDecimal(dollars), 120);
    }

    private ReservationRequest deepgramRequest(UUID meetingId, String dollars) {
        return new ReservationRequest(meetingId, "DEEPGRAM_POC", "nova-3", "TRANSCRIPTION", "synthetic-rates",
                new BigDecimal(dollars), 120);
    }

    private void restoreSettings(AppSettings before) {
        jdbc.update("""
                UPDATE app_settings SET monthly_budget_usd=?,warn_at_percent=?,per_meeting_max_minutes=?,
                       recording_retention_days=?,revision=? WHERE id=1
                """, before.monthlyBudgetUsd(), before.warnAtPercent(), before.perMeetingMaxMinutes(),
                before.recordingRetentionDays(), before.revision());
    }

    @Test
    void deferredCommitFailureRestoresRecordingQuarantine() throws Exception {
        Meeting meeting = meetings.create("Commit rollback " + UUID.randomUUID());
        Path chunk = recordingStorage.writeChunk(meeting.id(), 0, new byte[] {1, 2, 3});
        String table = "deletion_guard_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TABLE " + table + " (meeting_id UUID REFERENCES meetings(id) DEFERRABLE INITIALLY DEFERRED)");
        jdbc.update("INSERT INTO " + table + "(meeting_id) VALUES (?)", meeting.id());
        try {
            assertThatThrownBy(() -> deletions.delete(meeting.id(), meeting.revision()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(meetings.find(meeting.id()).id()).isEqualTo(meeting.id());
            assertThat(chunk).exists();
            assertThat(chunk.getParent().resolveSibling(".deleting-" + meeting.id())).doesNotExist();
        } finally {
            jdbc.execute("DROP TABLE " + table);
            deletions.delete(meeting.id(), meeting.revision());
        }
    }

    @Test
    void deletionWaitsForInFlightUploadMeetingLock() throws Exception {
        Meeting meeting = meetings.create("Upload serialization " + UUID.randomUUID(), InputSource.LOCAL_RECORDING);
        meetings.start(meeting.id());
        Meeting stopped = meetings.stop(meeting.id());
        byte[] content = "serialized upload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        CountDownLatch uploaded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> upload = executor.submit(() -> transaction.executeWithoutResult(status -> {
                recordingService.upload(meeting.id(), 0, 0, content.length, hash, "audio/webm", content);
                uploaded.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(uploaded.await(5, TimeUnit.SECONDS)).isTrue();
            Future<MeetingDeletionService.DeletionResult> deletion = executor.submit(
                    () -> deletions.delete(meeting.id(), stopped.revision()));
            assertThatThrownBy(() -> deletion.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            upload.get(5, TimeUnit.SECONDS);
            assertThat(deletion.get(5, TimeUnit.SECONDS).deleted()).isTrue();
        } finally {
            release.countDown();
        }
        assertThatThrownBy(() -> meetings.find(meeting.id())).isInstanceOf(ApiException.class);
    }

    @Test
    void concurrentDuplicateFinalsResolveToOneSegment() throws Exception {
        Meeting meeting = meetings.create("PostgreSQL idempotency test " + UUID.randomUUID());
        String eventId = "concurrent-" + UUID.randomUUID();
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TranscriptSegment> first = executor.submit(() -> {
                release.await();
                return meetings.insertFinal(meeting.id(), eventId, 1, 0, 1, "same", "speaker_0");
            });
            Future<TranscriptSegment> second = executor.submit(() -> {
                release.await();
                return meetings.insertFinal(meeting.id(), eventId, 1, 0, 1, "same", "speaker_0");
            });
            release.countDown();
            assertThat(first.get().id()).isEqualTo(second.get().id());
        }
        assertThat(meetings.transcript(meeting.id())).hasSize(1);
    }

    @Test
    void summaryMeetingLockSerializesCorrectionsAndRejectsLateFinals() throws Exception {
        Meeting meeting = meetings.create("Summary lock " + UUID.randomUUID());
        TranscriptSegment segment = meetings.insertFinal(meeting.id(), "lock-" + UUID.randomUUID(), 1, 0, 1,
                "before", null);
        meetings.stop(meeting.id());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> summary = executor.submit(() -> transaction.executeWithoutResult(status -> {
                summaries.lockMeeting(meeting.id());
                locked.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<TranscriptSegment> correction = executor.submit(() -> meetings.correct(
                    meeting.id(), segment.id(), 0, "after", "USER", null, true));
            Future<TranscriptSegment> lateFinal = executor.submit(() -> meetings.insertFinal(
                    meeting.id(), "late-" + UUID.randomUUID(), 2, 2, 3, "late", null));

            assertThatThrownBy(() -> correction.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> lateFinal.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            summary.get(5, TimeUnit.SECONDS);
            assertThat(correction.get(5, TimeUnit.SECONDS).displayText()).isEqualTo("after");
            assertThatThrownBy(() -> lateFinal.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseMessage("Stopped meeting transcripts cannot accept new final segments");
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static synchronized TemporaryPostgresDatabase isolatedDatabase() {
        if (isolatedDatabase == null) {
            isolatedDatabase = TemporaryPostgresDatabase.create(
                    System.getenv("APP_TEST_DATABASE_URL"),
                    env("APP_TEST_DATABASE_USER", "postgres"),
                    env("APP_TEST_DATABASE_PASSWORD", "postgres"));
        }
        return isolatedDatabase;
    }

    static synchronized void dropIsolatedDatabase() {
        if (isolatedDatabase != null) {
            isolatedDatabase.close();
            isolatedDatabase = null;
        }
    }
}
