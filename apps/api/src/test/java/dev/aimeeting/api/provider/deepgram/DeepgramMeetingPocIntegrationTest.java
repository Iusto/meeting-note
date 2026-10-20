package dev.aimeeting.api.provider.deepgram;

import static org.assertj.core.api.Assertions.*;

import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.provider.SpeechEvent;
import dev.aimeeting.api.topic.TopicRepository;
import dev.aimeeting.api.usage.ledger.CostLedgerService;
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
        jdbc.update("UPDATE runtime_state SET active_meeting_id=NULL WHERE id=1");
        jdbc.update("DELETE FROM ai_usage_records");
        jdbc.update("DELETE FROM meetings");
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
            assertThat(meetings.find(meetingId).status()).isEqualTo("STARTED");
        }
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

    private CompletableFuture<DeepgramMeetingPoc> open(Duration duration, long offset) {
        return DeepgramMeetingPoc.openLoopback(endpoint(), requestId, meetingId, offset, true,
                limits(duration), "synthetic-rates", meetings, topics, ledger, events::add);
    }
    private URI endpoint() { return URI.create("ws://127.0.0.1:" + port + "/v1/listen"); }
    private PocLimits limits(Duration duration) { return new PocLimits(duration, BigDecimal.ONE, new BigDecimal("0.10")); }
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
    private String result(boolean finalized) {
        return "{\"type\":\"Results\",\"channel_index\":[0,1],\"start\":0,\"duration\":1,\"is_final\":" + finalized
                + ",\"channel\":{\"alternatives\":[{\"transcript\":\"회의를 시작합니다.\",\"words\":[]}]}}";
    }

    @TestConfiguration
    @EnableTransactionManagement
    @Import({MeetingRepository.class, TopicRepository.class, CostLedgerService.class})
    static class Services {}
}
