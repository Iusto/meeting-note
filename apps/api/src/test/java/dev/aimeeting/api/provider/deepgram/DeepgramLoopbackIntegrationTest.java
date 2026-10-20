package dev.aimeeting.api.provider.deepgram;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Real JDK WebSocket transport, exclusively against a loopback fake provider. */
@SpringBootTest(classes = DeepgramLoopbackIntegrationTest.FakeServer.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"})
@ActiveProfiles("deepgram-loopback-test")
class DeepgramLoopbackIntegrationTest {
    @LocalServerPort int port;
    @Autowired FakeProvider fake;

    @BeforeEach
    void reset() {
        fake.handshakes.clear();
        fake.connections.clear();
        fake.audio.clear();
        fake.controls.clear();
    }

    @AfterEach
    void disconnect() throws Exception {
        for (WebSocketSession socket : Set.copyOf(fake.open)) {
            if (socket.isOpen()) socket.close();
        }
    }

    @Test
    void exchangesOrderedContainerBytesAndDrainsFinalResultsBeforeClosing() throws Exception {
        BlockingQueue<DeepgramMessage> messages = new LinkedBlockingQueue<>();
        try (DeepgramPocSession client = connect(Duration.ofSeconds(15), messages)) {
            Handshake handshake = fake.handshakes.poll(5, TimeUnit.SECONDS);
            assertThat(handshake).isNotNull();
            assertThat(handshake.authorization()).isEqualTo("Token offline-test-token");
            assertThat(handshake.query()).contains("model=nova-3", "language=ko", "interim_results=true", "diarize_model=latest", "keyterm=MES");
            assertThat(handshake.query()).doesNotContain("encoding=", "sample_rate=", "offline-test-token");
            WebSocketSession server = fake.connections.poll(5, TimeUnit.SECONDS);
            assertThat(server).isNotNull();

            byte[] first = {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, 1};
            byte[] second = {2, 3, 4};
            client.sendAudio(new byte[0]).get(5, TimeUnit.SECONDS);
            var firstSend = client.sendAudio(first);
            var secondSend = client.sendAudio(second);
            firstSend.get(5, TimeUnit.SECONDS);
            secondSend.get(5, TimeUnit.SECONDS);
            assertThat(fake.audio.poll(5, TimeUnit.SECONDS)).containsExactly(first);
            assertThat(fake.audio.poll(5, TimeUnit.SECONDS)).containsExactly(second);

            String partial = result("임시", false, false);
            server.sendMessage(new TextMessage(partial.substring(0, 19), false));
            server.sendMessage(new TextMessage(partial.substring(19), true));
            assertThat(messages.poll(5, TimeUnit.SECONDS)).isInstanceOfSatisfying(DeepgramMessage.Results.class,
                    event -> { assertThat(event.transcript()).isEqualTo("임시"); assertThat(event.finalResult()).isFalse(); });

            var finishing = client.finish();
            assertThat(fake.controls.poll(5, TimeUnit.SECONDS)).isEqualTo("{\"type\":\"CloseStream\"}");
            server.sendMessage(new TextMessage(result("확정 LOT-100", true, true)));
            server.sendMessage(new TextMessage("{\"type\":\"Metadata\",\"request_id\":\"synthetic-request\"}"));
            server.close(CloseStatus.NORMAL);
            assertThat(client.completion().get(5, TimeUnit.SECONDS)).isEqualTo(DeepgramPocSession.StopReason.COMPLETED);
            finishing.get(5, TimeUnit.SECONDS);
            assertThat(messages.poll(5, TimeUnit.SECONDS)).isInstanceOfSatisfying(DeepgramMessage.Results.class,
                    event -> { assertThat(event.transcript()).isEqualTo("확정 LOT-100"); assertThat(event.finalResult()).isTrue(); assertThat(event.speechFinal()).isTrue(); });
            assertThat(messages.poll(5, TimeUnit.SECONDS)).isEqualTo(new DeepgramMessage.Metadata("synthetic-request"));
            assertThat(fake.audio).isEmpty();
            assertThat(client.sendAudio(new byte[]{1})).isCompletedExceptionally();
        }
    }

    @Test
    void sendsTextKeepAliveWhileIdleAndStopsAtTheAbsoluteTimeLimit() throws Exception {
        try (DeepgramPocSession client = connect(Duration.ofSeconds(5), new LinkedBlockingQueue<>())) {
            assertThat(fake.controls.poll(5, TimeUnit.SECONDS)).isEqualTo("{\"type\":\"KeepAlive\"}");
            assertThat(client.completion().get(5, TimeUnit.SECONDS)).isEqualTo(DeepgramPocSession.StopReason.TIME_LIMIT);
            assertThat(fake.audio).isEmpty();
        }
    }

    @Test
    void sanitizesNonFatalProviderErrorsAndContinuesReceivingResults() throws Exception {
        BlockingQueue<DeepgramMessage> messages = new LinkedBlockingQueue<>();
        try (DeepgramPocSession client = connect(Duration.ofSeconds(10), messages)) {
            WebSocketSession server = fake.connections.poll(5, TimeUnit.SECONDS);
            assertThat(server).isNotNull();
            server.sendMessage(new TextMessage("{\"type\":\"Error\",\"description\":\"synthetic-sensitive-key-and-text\",\"message\":\"private\"}"));
            assertThat(messages.poll(5, TimeUnit.SECONDS)).isInstanceOfSatisfying(DeepgramMessage.Error.class,
                    error -> assertThat(error.toString()).doesNotContain("sensitive", "private"));
            server.sendMessage(new TextMessage(result("after error", true, true)));
            assertThat(messages.poll(5, TimeUnit.SECONDS)).isInstanceOfSatisfying(DeepgramMessage.Results.class,
                    result -> assertThat(result.transcript()).isEqualTo("after error"));
            assertThat(client.completion()).isNotDone();

            client.finish();
            assertThat(fake.controls.poll(5, TimeUnit.SECONDS)).isEqualTo("{\"type\":\"CloseStream\"}");
            server.sendMessage(new TextMessage("{\"type\":\"Metadata\",\"request_id\":\"synthetic-request\"}"));
            server.close(CloseStatus.NORMAL);
            assertThat(client.completion().get(5, TimeUnit.SECONDS))
                    .isEqualTo(DeepgramPocSession.StopReason.COMPLETED);
        }
    }

    @Test
    void rejectsOversizedFragmentedTextWithoutRetainingTheProviderPayload() throws Exception {
        try (DeepgramPocSession client = connect(Duration.ofSeconds(10), new LinkedBlockingQueue<>())) {
            WebSocketSession server = fake.connections.poll(5, TimeUnit.SECONDS);
            assertThat(server).isNotNull();
            server.sendMessage(new TextMessage("x".repeat(131_073), false));
            server.sendMessage(new TextMessage("y".repeat(131_073), true));
            assertThat(client.completion().get(5, TimeUnit.SECONDS)).isEqualTo(DeepgramPocSession.StopReason.INVALID_MESSAGE);
        }
    }

    @Test
    void appliesTheDeadlineEvenWhenTheWebSocketHandshakeNeverCompletes() throws Exception {
        try (ServerSocket stalledServer = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            stalledServer.setSoTimeout(5_000);
            var connecting = DeepgramPocSession.connectLoopback(
                    URI.create("ws://127.0.0.1:" + stalledServer.getLocalPort() + "/v1/listen"),
                    new DeepgramOptions(false, List.of()), limits(Duration.ofSeconds(1)), ignored -> {});
            try (var stalledPeer = stalledServer.accept()) {
                assertThat(stalledPeer.isConnected()).isTrue();
                assertThatThrownBy(() -> connecting.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            }
        }
    }

    private DeepgramPocSession connect(Duration duration, BlockingQueue<DeepgramMessage> messages) throws Exception {
        return DeepgramPocSession.connectLoopback(URI.create("ws://127.0.0.1:" + port + "/v1/listen"),
                new DeepgramOptions(true, List.of("MES")), limits(duration), messages::add).get(5, TimeUnit.SECONDS);
    }

    private PocLimits limits(Duration duration) {
        return new PocLimits(duration, new BigDecimal("1.00"), new BigDecimal("0.10"));
    }

    private String result(String text, boolean finalized, boolean speechFinal) {
        return "{\"type\":\"Results\",\"channel_index\":[0,1],\"start\":0,\"duration\":1,\"is_final\":" + finalized
                + ",\"speech_final\":" + speechFinal + ",\"channel\":{\"alternatives\":[{\"transcript\":\"" + text + "\",\"words\":[]}]}}";
    }

    private record Handshake(String authorization, String query) {}

    static class FakeProvider extends AbstractWebSocketHandler implements HandshakeInterceptor {
        final BlockingQueue<Handshake> handshakes = new LinkedBlockingQueue<>();
        final BlockingQueue<WebSocketSession> connections = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> audio = new LinkedBlockingQueue<>();
        final BlockingQueue<String> controls = new LinkedBlockingQueue<>();
        final Set<WebSocketSession> open = ConcurrentHashMap.newKeySet();

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Map<String, Object> attributes) {
            handshakes.add(new Handshake(request.getHeaders().getFirst("Authorization"), request.getURI().getRawQuery()));
            return true;
        }

        @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {}
        @Override public void afterConnectionEstablished(WebSocketSession session) {
            session.setBinaryMessageSizeLimit(1_048_576);
            open.add(session);
            connections.add(session);
        }
        @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { open.remove(session); }
        @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) { controls.add(message.getPayload()); }

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            ByteBuffer data = message.getPayload();
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            audio.add(bytes);
        }
    }

    @Configuration
    @Profile("deepgram-loopback-test")
    @EnableAutoConfiguration
    @EnableWebSocket
    static class FakeServer implements WebSocketConfigurer {
        @Bean FakeProvider fakeProvider() { return new FakeProvider(); }
        @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(fakeProvider(), "/v1/listen").addInterceptors(fakeProvider());
        }
    }
}
