package dev.aimeeting.api.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aimeeting.api.auth.SessionService;
import dev.aimeeting.api.meeting.InputSource;
import dev.aimeeting.api.meeting.MeetingRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:watchintegration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.fake-event-delay-ms=300"
})
class MeetingWatchIntegrationTest {
    private static final String ORIGIN = "http://localhost:5173";
    @LocalServerPort int port;
    @Autowired SessionService sessions;
    @Autowired MeetingRepository meetings;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void explicitStopNotifiesAnIdleViewerAndKeepsTheViewingSocketOpen() throws Exception {
        var meeting = meetings.create("Synthetic local watch stop", InputSource.LOCAL_RECORDING, null);
        meetings.start(meeting.id());
        String cookie = SessionService.COOKIE_NAME + "=" + sessions.login("test-owner-token-00000000000000000");
        try (HttpClient client = HttpClient.newHttpClient()) {
            Events events = new Events();
            WebSocket viewer = client.newWebSocketBuilder().header("Origin", ORIGIN).header("Cookie", cookie)
                    .buildAsync(URI.create("ws://localhost:" + port + "/api/v1/meetings/" + meeting.id() + "/live?mode=watch"), events)
                    .get(5, TimeUnit.SECONDS);
            try {
                events.await("session.ready");
                var result = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/meetings/" + meeting.id() + "/stop"))
                        .header("Origin", ORIGIN).header("Cookie", cookie).POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(result.statusCode()).isEqualTo(200);
                assertThat(events.await("stream.stopped").path("transcriptStatus").asText()).isEqualTo("STOPPED_BY_USER");
                assertThat(viewer.isInputClosed()).isFalse();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stream_sessions WHERE meeting_id=?", Integer.class, meeting.id())).isZero();
            } finally { viewer.abort(); }
        }
    }

    @Test
    void authenticatedDevicesWatchOneMockRunAndRemainingViewerSurvivesAnotherClosing() throws Exception {
        var meeting = meetings.create("Synthetic cross-device watch", InputSource.MOCK, null);
        String auth = sessions.login("test-owner-token-00000000000000000");
        String cookie = SessionService.COOKIE_NAME + "=" + auth;
        try (HttpClient client = HttpClient.newHttpClient()) {
            Events first = new Events();
            Events second = new Events();
            URI uri = URI.create("ws://localhost:" + port + "/api/v1/meetings/" + meeting.id() + "/live?mode=watch");
            WebSocket phone = client.newWebSocketBuilder().header("Origin", ORIGIN).header("Cookie", cookie)
                    .buildAsync(uri, first).get(5, TimeUnit.SECONDS);
            WebSocket laptop = client.newWebSocketBuilder().header("Origin", ORIGIN).header("Cookie", cookie)
                    .buildAsync(uri, second).get(5, TimeUnit.SECONDS);
            try {
                first.await("session.ready");
                second.await("session.ready");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stream_sessions WHERE meeting_id=?", Integer.class, meeting.id())).isZero();
                HttpResponse<String> start = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/meetings/" + meeting.id() + "/start"))
                        .header("Origin", ORIGIN).header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(start.statusCode()).isEqualTo(200);
                first.await("transcript.partial");
                second.await("transcript.partial");
                laptop.sendClose(WebSocket.NORMAL_CLOSURE, "viewer done").get(5, TimeUnit.SECONDS);
                assertThat(meetings.find(meeting.id()).status()).isEqualTo("STARTED");
                JsonNode result = first.await("transcript.final");
                assertThat(result.get("originalText").asText()).contains("이번 주");
                first.await("speaker.updated");
                first.await("transcript.corrected");
                first.await("stream.stopped");
                assertThat(meetings.find(meeting.id()).status()).isEqualTo("STOPPED");
                assertThat(meetings.transcript(meeting.id())).hasSize(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stream_sessions WHERE meeting_id=?", Integer.class, meeting.id())).isEqualTo(1);
            } finally { phone.abort(); laptop.abort(); }
        }
    }

    private final class Events implements WebSocket.Listener {
        private final LinkedBlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        private final StringBuilder text = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                try { received.add(mapper.readTree(text.toString())); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
                text.setLength(0);
            }
            socket.request(1);
            return null;
        }
        JsonNode await(String type) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode event = received.poll(100, TimeUnit.MILLISECONDS);
                if (event != null && type.equals(event.path("type").asText())) return event;
            }
            throw new AssertionError("Missing watch event: " + type);
        }
    }
}
