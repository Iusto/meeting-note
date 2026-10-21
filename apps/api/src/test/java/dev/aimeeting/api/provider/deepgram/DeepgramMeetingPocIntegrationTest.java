package dev.aimeeting.api.provider.deepgram;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.provider.SpeechEvent;
import dev.aimeeting.api.topic.TopicRepository;
import dev.aimeeting.api.usage.ledger.CostLedgerService;
import dev.aimeeting.api.usage.ledger.ReservationRequest;
import dev.aimeeting.api.web.ApiException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** Real WebSocket + H2 + ledger rehearsal, with no remote endpoint or credentials. */
@SpringBootTest(classes = {DeepgramLoopbackIntegrationTest.FakeServer.class,
        DeepgramMeetingPocIntegrationTest.Services.class}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1",
                "spring.datasource.url=jdbc:h2:mem:deepgram-meeting-poc;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("deepgram-loopback-test")
class DeepgramMeetingPocIntegrationTest {
    @LocalServerPort int port;
    @Autowired DeepgramLoopbackIntegrationTest.FakeProvider fake;
    @Autowired MeetingRepository meetings;
    @Autowired TopicRepository topics;
    @Autowired CostLedgerService ledger;
    @Autowired JdbcTemplate jdbc;
    UUID meetingId;
    UUID requestId;
    BlockingQueue<SpeechEvent> events;

    @BeforeEach void reset() throws Exception {
        for (WebSocketSession open : List.copyOf(fake.open)) if (open.isOpen()) open.close();
        fake.connections.clear(); fake.audio.clear(); fake.handshakes.clear(); fake.controls.clear();
        jdbc.update("DELETE FROM active_deepgram_sessions");
        jdbc.update("UPDATE runtime_state SET active_meeting_id=NULL WHERE id=1");
        jdbc.update("DELETE FROM meetings");
        jdbc.update("DELETE FROM ai_usage_records");
        jdbc.update("UPDATE app_settings SET monthly_budget_usd=10,per_meeting_max_minutes=240 WHERE id=1");
        meetingId = meetings.create("Synthetic integration rehearsal").id();
        meetings.start(meetingId);
        requestId = UUID.randomUUID();
        events = new LinkedBlockingQueue<>();
    }

    @Test void persistsFinalsAndSettlesEstimatedPcmUsageAfterFinalization() throws Exception {
        try (var poc = open(Duration.ofSeconds(10), 5_000).get(5, TimeUnit.SECONDS)) {
            var server = server();
            assertThat(server.getUri().getRawQuery()).contains("encoding=linear16", "sample_rate=16000", "channels=1");
            byte[] pcm = new byte[32_000];
            pcm[0] = 42;
            poc.sendAudio(pcm).get(5, TimeUnit.SECONDS);
            assertThat(fake.audio.poll(5, TimeUnit.SECONDS)).isEqualTo(pcm);
            server.sendMessage(new TextMessage(result(false)));
            assertThat(event("transcript.partial").startMs()).isEqualTo(5_000);
            assertThat(meetings.transcript(meetingId)).isEmpty();
            server.sendMessage(new TextMessage(result(true)));
            assertThat(event("transcript.final").originalText()).isEqualTo("회의를 시작합니다.");
            var complete = poc.finish();
            finishServer(server);
            assertThat(complete.get(5, TimeUnit.SECONDS)).isEqualTo(DeepgramPocSession.StopReason.COMPLETED);
            assertThat(meetings.transcript(meetingId)).singleElement().satisfies(segment -> {
                assertThat(segment.startMs()).isEqualTo(5_000);
                assertThat(segment.originalText()).isEqualTo("회의를 시작합니다.");
            });
            assertThat(state()).isEqualTo("SETTLED");
            assertThat(jdbc.queryForObject("SELECT estimated_usd FROM ai_usage_records WHERE id=?", BigDecimal.class, requestId))
                    .isEqualByComparingTo("0.001667");
            assertThat(jdbc.queryForObject("SELECT actual_usd FROM ai_usage_records WHERE id=?", BigDecimal.class, requestId)).isNull();
            assertThat(jdbc.queryForObject("SELECT provider_session_id FROM stream_sessions WHERE id=?", String.class,
                    poc.streamSessionId())).isEqualTo("offline-session");
            assertThat(meetings.find(meetingId).status()).isEqualTo("STARTED");
        }
    }

    @Test void activeSessionClaimAndReconnectOffsetPreventAudioReplay() {
        UUID firstRequest = UUID.randomUUID();
        reserve(firstRequest, meetingId);
        UUID first = meetings.createDeepgramStreamSession(meetingId, 0, firstRequest);
        UUID duplicateRequest = UUID.randomUUID();
        reserve(duplicateRequest, meetingId);
        assertThatThrownBy(() -> meetings.createDeepgramStreamSession(meetingId, 0, duplicateRequest))
                .isInstanceOf(ApiException.class).hasMessageContaining("already active");
        meetings.closeStreamSession(first, "SYNTHETIC_RECONNECT");
        meetings.insertFinal(meetingId, "confirmed-before-reconnect", 1, 0, 1_000, "confirmed", null);

        UUID replayRequest = UUID.randomUUID();
        reserve(replayRequest, meetingId);
        assertThatThrownBy(() -> meetings.createDeepgramStreamSession(meetingId, 999, replayRequest))
                .isInstanceOf(ApiException.class).hasMessageContaining("offset");
        UUID reconnectRequest = UUID.randomUUID();
        reserve(reconnectRequest, meetingId);
        UUID reconnect = meetings.createDeepgramStreamSession(meetingId, 1_000, reconnectRequest);
        assertThat(jdbc.queryForObject("SELECT audio_offset_ms FROM stream_sessions WHERE id=?", Long.class, reconnect))
                .isEqualTo(1_000L);
        meetings.closeStreamSession(reconnect, "SYNTHETIC_COMPLETE");
    }

    @Test void sessionClaimRechecksThatMeetingIsStillStarted() {
        meetings.stop(meetingId);
        reserve(requestId, meetingId);
        assertThatThrownBy(() -> meetings.createDeepgramStreamSession(meetingId, 0, requestId))
                .isInstanceOf(ApiException.class).hasMessageContaining("started");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stream_sessions WHERE meeting_id=?", Integer.class, meetingId))
                .isZero();
    }

    @Test void localClaimAndOffsetRejectionsReleaseReservationWithoutOpeningTransport() throws Exception {
        UUID activeRequest = UUID.randomUUID();
        reserve(activeRequest, meetingId);
        UUID active = meetings.createDeepgramStreamSession(meetingId, 0, activeRequest);
        UUID activeRejected = UUID.randomUUID();
        var activeFailure = DeepgramMeetingPoc.openLoopback(endpoint(), activeRejected, meetingId, 0, true,
                limits(Duration.ofSeconds(10)), "synthetic-rates", meetings, topics, ledger, events::add);
        assertThatThrownBy(() -> activeFailure.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(state(activeRejected)).isEqualTo("RELEASED");
        assertThat(fake.connections).isEmpty();

        meetings.closeStreamSession(active, "SYNTHETIC_RECONNECT");
        meetings.insertFinal(meetingId, "confirmed-reconnect-boundary", 1, 0, 1_000, "confirmed", null);
        UUID offsetRejected = UUID.randomUUID();
        var offsetFailure = DeepgramMeetingPoc.openLoopback(endpoint(), offsetRejected, meetingId, 999, true,
                limits(Duration.ofSeconds(10)), "synthetic-rates", meetings, topics, ledger, events::add);
        assertThatThrownBy(() -> offsetFailure.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(state(offsetRejected)).isEqualTo("RELEASED");
        assertThat(fake.connections).isEmpty();
    }

    @Test void providerFailureRetainsReservationAndDoesNotStopTheMeeting() throws Exception {
        try (var poc = open(Duration.ofSeconds(10), 0).get(5, TimeUnit.SECONDS)) {
            server().close(CloseStatus.SERVER_ERROR);
            assertThat(poc.completion().get(5, TimeUnit.SECONDS)).isEqualTo(DeepgramPocSession.StopReason.PROVIDER_ERROR);
            assertThat(state()).isEqualTo("UNCERTAIN");
            assertThat(jdbc.queryForObject("SELECT reserved_usd FROM ai_usage_records WHERE id=?", BigDecimal.class, requestId))
                    .isGreaterThan(BigDecimal.ZERO);
            assertThat(meetings.find(meetingId).status()).isEqualTo("STARTED");
            assertThat(jdbc.queryForObject("SELECT close_reason FROM stream_sessions WHERE id=?", String.class, poc.streamSessionId()))
                    .isEqualTo("PROVIDER_ERROR");
        }
    }

    @Test void duplicateRequestCannotOpenAnotherProviderConnection() throws Exception {
        try (var poc = open(Duration.ofSeconds(10), 0).get(5, TimeUnit.SECONDS)) {
            server();
            assertThatThrownBy(() -> open(Duration.ofSeconds(10), 0)).isInstanceOf(ApiException.class);
            assertThat(fake.connections).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stream_sessions WHERE meeting_id=?", Integer.class, meetingId)).isEqualTo(1);
            assertThat(state()).isEqualTo("DISPATCHED");
            poc.close();
            poc.completion().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void zeroBudgetAndRemoteEndpointsFailBeforeAConnection() {
        jdbc.update("UPDATE app_settings SET monthly_budget_usd=0 WHERE id=1");
        assertThatThrownBy(() -> open(Duration.ofSeconds(10), 0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DeepgramMeetingPoc.openLoopback(URI.create("wss://api.deepgram.com/v1/listen"),
                requestId, meetingId, 0, true, limits(Duration.ofSeconds(10)), "synthetic-rates", meetings, topics, ledger, events::add))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fake.connections).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_records", Integer.class)).isZero();
    }

    @Test void pcmDurationLimitCannotBeBypassedBySendingAudioFasterThanRealtime() throws Exception {
        try (var poc = open(Duration.ofSeconds(10), 0).get(5, TimeUnit.SECONDS)) {
            server();
            assertThatThrownBy(() -> poc.sendAudio(new byte[] {1}).join()).hasCauseInstanceOf(IllegalArgumentException.class);
            for (int i = 0; i < 5; i++) poc.sendAudio(new byte[64_000]).get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> poc.sendAudio(new byte[] {1, 2}).join()).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(poc.completion().get(5, TimeUnit.SECONDS)).isEqualTo(DeepgramPocSession.StopReason.USER_STOP);
            assertThat(state()).isEqualTo("UNCERTAIN");
        }
    }

    @Test void persistenceConsumerFailureIsReportedWithoutExposingTranscriptOrStoppingRecording() throws Exception {
        var opening = DeepgramMeetingPoc.openLoopback(endpoint(), requestId, meetingId, 0, true,
                limits(Duration.ofSeconds(10)), "synthetic-rates", meetings, topics, ledger,
                event -> { throw new IllegalStateException("sensitive callback contents"); });
        try (var poc = opening.get(5, TimeUnit.SECONDS)) {
            server().sendMessage(new TextMessage(result(true)));
            assertThatThrownBy(() -> poc.completion().get(5, TimeUnit.SECONDS))
                    .hasMessageNotContaining("sensitive").hasMessageNotContaining("회의를")
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(state()).isEqualTo("UNCERTAIN");
            assertThat(meetings.find(meetingId).status()).isEqualTo("STARTED");
        }
    }

    @Test void settlementFailureStillClosesStreamAndRetainsUncertainReservation() throws Exception {
        CostLedgerService faultLedger = spy(ledger);
        doThrow(new IllegalStateException("synthetic settlement failure"))
                .when(faultLedger).settle(eq(requestId), any());
        var opening = DeepgramMeetingPoc.openLoopback(endpoint(), requestId, meetingId, 0, true,
                limits(Duration.ofSeconds(10)), "synthetic-rates", meetings, topics, faultLedger, events::add);
        try (var poc = opening.get(5, TimeUnit.SECONDS)) {
            WebSocketSession server = server();
            var complete = poc.finish();
            finishServer(server);
            assertThatThrownBy(() -> complete.get(5, TimeUnit.SECONDS))
                    .hasMessageNotContaining("synthetic settlement failure");
            assertThat(state()).isEqualTo("UNCERTAIN");
            assertThat(jdbc.queryForObject("SELECT ended_at IS NOT NULL FROM stream_sessions WHERE id=?", Boolean.class,
                    poc.streamSessionId())).isTrue();
        }
    }

    @Test void streamCloseFailureStillMarksLedgerUncertain() throws Exception {
        AtomicBoolean closeAttempted = new AtomicBoolean();
        DeepgramMeetingPoc.SessionCloser faultCloser = (sessionId, reason) -> {
            closeAttempted.set(true);
            throw new IllegalStateException("synthetic stream close failure");
        };
        var opening = DeepgramMeetingPoc.openLoopback(endpoint(), requestId, meetingId, 0, true,
                limits(Duration.ofSeconds(10)), "synthetic-rates", meetings, topics, ledger, events::add, faultCloser);
        try (var poc = opening.get(5, TimeUnit.SECONDS)) {
            WebSocketSession server = server();
            var complete = poc.finish();
            finishServer(server);
            assertThatThrownBy(() -> complete.get(5, TimeUnit.SECONDS))
                    .hasMessageNotContaining("synthetic stream close failure");
            assertThat(closeAttempted).isTrue();
            assertThat(state()).isEqualTo("UNCERTAIN");
        }
    }

    @Test void startupRecoveryReconcilesLinkedAndUnlinkedUsageInOnePass() {
        UUID unlinkedReserved = UUID.randomUUID();
        reserve(unlinkedReserved, meetingId);
        UUID unlinkedDispatched = UUID.randomUUID();
        reserve(unlinkedDispatched, meetingId);
        ledger.startDispatch(unlinkedDispatched);
        UUID linkedDispatched = UUID.randomUUID();
        reserve(linkedDispatched, meetingId);
        UUID linkedSession = meetings.createDeepgramStreamSession(meetingId, 0, linkedDispatched);
        ledger.startDispatch(linkedDispatched);
        UUID terminal = UUID.randomUUID();
        reserve(terminal, meetingId);
        ledger.release(terminal);

        assertThat(meetings.recoverOrphanedDeepgramSessions()).isEqualTo(1);

        assertThat(state(unlinkedReserved)).isEqualTo("RELEASED");
        assertThat(state(unlinkedDispatched)).isEqualTo("UNCERTAIN");
        assertThat(state(linkedDispatched)).isEqualTo("UNCERTAIN");
        assertThat(state(terminal)).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT close_reason FROM stream_sessions WHERE id=?", String.class, linkedSession))
                .isEqualTo("APP_RESTART");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_deepgram_sessions", Integer.class)).isZero();
    }

    @Test void startupRecoveryClosesLegacyPocStreamsWithoutChangingCompletedOrOtherProviders() {
        UUID legacy = meetings.createStreamSession(meetingId, "DEEPGRAM_POC", 0);
        UUID completed = meetings.createStreamSession(meetingId, "DEEPGRAM_POC", 0);
        meetings.closeStreamSession(completed, "COMPLETED");
        UUID fakeSession = meetings.createStreamSession(meetingId, "FAKE", 0);

        assertThat(meetings.recoverOrphanedDeepgramSessions()).isZero();

        assertThat(jdbc.queryForObject("SELECT close_reason FROM stream_sessions WHERE id=?", String.class, legacy))
                .isEqualTo("APP_RESTART");
        assertThat(jdbc.queryForObject("SELECT ended_at IS NOT NULL FROM stream_sessions WHERE id=?", Boolean.class, legacy))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT close_reason FROM stream_sessions WHERE id=?", String.class, completed))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT ended_at IS NULL FROM stream_sessions WHERE id=?", Boolean.class, fakeSession))
                .isTrue();
        assertThat(meetings.recoverOrphanedDeepgramSessions()).isZero();
    }

    @Test void timedOutWriterKeepsClaimUntilItActuallyStopsThenAllowsSafeReconnect() throws Exception {
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        DeepgramMeetingPoc.PersistenceHook blockingWriter = message -> {
            if (!(message instanceof DeepgramMessage.Results result) || !result.finalResult()) return;
            writerEntered.countDown();
            while (releaseWriter.getCount() > 0) {
                try {
                    releaseWriter.await();
                } catch (InterruptedException failure) {
                    // Deliberately ignore shutdownNow to reproduce a stuck provider persistence writer.
                }
            }
        };
        var opening = DeepgramMeetingPoc.openLoopback(endpoint(), requestId, meetingId, 0, true,
                limits(Duration.ofSeconds(20)), "synthetic-rates", meetings, topics, ledger, events::add,
                meetings::closeStreamSession, blockingWriter);
        try (var poc = opening.get(5, TimeUnit.SECONDS)) {
            WebSocketSession firstServer = server();
            firstServer.sendMessage(new TextMessage(result(true)));
            assertThat(writerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var complete = poc.finish();
            finishServer(firstServer);
            assertThatThrownBy(() -> complete.get(8, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(state()).isEqualTo("UNCERTAIN");
            assertThat(meetings.transcript(meetingId)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_deepgram_sessions WHERE meeting_id=?",
                    Integer.class, meetingId)).isEqualTo(1);

            UUID blockedRequest = UUID.randomUUID();
            var blocked = DeepgramMeetingPoc.openLoopback(endpoint(), blockedRequest, meetingId, 1_000, true,
                    limits(Duration.ofSeconds(10)), "synthetic-rates", meetings, topics, ledger, events::add);
            assertThatThrownBy(() -> blocked.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(state(blockedRequest)).isEqualTo("RELEASED");
            assertThat(fake.connections).isEmpty();

            releaseWriter.countDown();
            awaitNoActiveClaim();
            UUID reconnectRequest = UUID.randomUUID();
            var reconnect = DeepgramMeetingPoc.openLoopback(endpoint(), reconnectRequest, meetingId, 1_000, true,
                    limits(Duration.ofSeconds(10)), "synthetic-rates", meetings, topics, ledger, events::add);
            try (var next = reconnect.get(5, TimeUnit.SECONDS)) {
                WebSocketSession nextServer = server();
                var nextComplete = next.finish();
                finishServer(nextServer);
                assertThat(nextComplete.get(5, TimeUnit.SECONDS)).isEqualTo(DeepgramPocSession.StopReason.COMPLETED);
            }
            assertThat(meetings.transcript(meetingId)).singleElement()
                    .satisfies(segment -> assertThat(segment.endMs()).isEqualTo(1_000));
        } finally {
            releaseWriter.countDown();
        }
    }

    private CompletableFuture<DeepgramMeetingPoc> open(Duration duration, long offset) {
        return DeepgramMeetingPoc.openLoopback(endpoint(), requestId, meetingId, offset, true,
                limits(duration), "synthetic-rates", meetings, topics, ledger, events::add);
    }
    private URI endpoint() { return URI.create("ws://127.0.0.1:" + port + "/v1/listen"); }
    private PocLimits limits(Duration duration) { return new PocLimits(duration, BigDecimal.ONE, new BigDecimal("0.10")); }
    private void reserve(UUID id, UUID targetMeeting) {
        ledger.reserve(id, new ReservationRequest(targetMeeting, "DEEPGRAM_POC", "nova-3", "TRANSCRIPTION",
                "synthetic-rates", BigDecimal.ONE, 10));
    }
    private WebSocketSession server() throws Exception {
        WebSocketSession server = fake.connections.poll(5, TimeUnit.SECONDS);
        assertThat(server).isNotNull();
        return server;
    }
    private SpeechEvent event(String type) throws Exception {
        SpeechEvent event = events.poll(5, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.type()).isEqualTo(type);
        return event;
    }
    private void finishServer(WebSocketSession server) throws Exception {
        String control;
        do { control = fake.controls.poll(5, TimeUnit.SECONDS); assertThat(control).isNotNull(); }
        while (!control.contains("CloseStream"));
        server.sendMessage(new TextMessage("{\"type\":\"Metadata\",\"request_id\":\"offline-session\"}"));
        server.close(CloseStatus.NORMAL);
    }
    private String state() { return jdbc.queryForObject("SELECT state FROM ai_usage_records WHERE id=?", String.class, requestId); }
    private String state(UUID id) { return jdbc.queryForObject("SELECT state FROM ai_usage_records WHERE id=?", String.class, id); }
    private void awaitNoActiveClaim() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM active_deepgram_sessions WHERE meeting_id=?",
                    Integer.class, meetingId) == 0) return;
            Thread.sleep(20);
        }
        fail("Timed-out persistence writer did not release its claim after stopping");
    }
    private String result(boolean finalized) {
        return "{\"type\":\"Results\",\"channel_index\":[0,1],\"start\":0,\"duration\":1,\"is_final\":" + finalized
                + ",\"channel\":{\"alternatives\":[{\"transcript\":\"회의를 시작합니다.\",\"words\":[]}]}}";
    }

    @TestConfiguration
    @EnableTransactionManagement
    @Import({MeetingRepository.class, TopicRepository.class, CostLedgerService.class})
    static class Services {}
}
