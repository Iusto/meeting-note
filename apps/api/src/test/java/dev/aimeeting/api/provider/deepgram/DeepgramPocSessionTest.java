package dev.aimeeting.api.provider.deepgram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeepgramPocSessionTest {
    private final PocLimits limits = new PocLimits(Duration.ofMinutes(1), BigDecimal.ONE,
            new BigDecimal("0.01"));

    @Test
    void disabledOptInFailsSynchronouslyBeforeOtherArgumentsOrNetwork() {
        assertThatThrownBy(() -> DeepgramPocSession.connect(false, null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Paid Deepgram POC access is disabled");
    }

    @Test
    void rejectsUnsafeKeysWithoutEchoingThem() {
        String secret = "secret value";
        assertThatThrownBy(() -> DeepgramPocSession.connect(true, secret,
                new DeepgramOptions(false, List.of()), limits, ignored -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Deepgram API key")
                .hasMessageNotContaining(secret);

        assertThatThrownBy(() -> DeepgramPocSession.connect(true, "secret\rkey",
                new DeepgramOptions(false, List.of()), limits, ignored -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret");
    }

    @Test
    void loopbackOnlyAcceptsLiteralWsAddressesAndExactPath() {
        DeepgramOptions options = new DeepgramOptions(false, List.of());
        List<URI> rejected = List.of(
                URI.create("ws://localhost:8080/v1/listen"),
                URI.create("wss://127.0.0.1:8080/v1/listen"),
                URI.create("ws://127.0.0.1/v1/listen"),
                URI.create("ws://127.0.0.1:8080/other"),
                URI.create("ws://user@127.0.0.1:8080/v1/listen"),
                URI.create("ws://127.0.0.1:8080/v1/listen?q=x"),
                URI.create("ws://127.0.0.1:8080/v1/listen#fragment"));

        for (URI endpoint : rejected) {
            assertThatThrownBy(() -> DeepgramPocSession.connectLoopback(
                    endpoint, options, limits, ignored -> {}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid offline Deepgram endpoint");
        }
    }

    @Test
    void defensivelyCopiesAudioAndPreservesSendOrder() {
        WebSocket socket = mock(WebSocket.class);
        CompletableFuture<WebSocket> firstSend = new CompletableFuture<>();
        CompletableFuture<WebSocket> secondSend = new CompletableFuture<>();
        when(socket.sendBinary(any(ByteBuffer.class), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(firstSend, secondSend);
        DeepgramPocSession session = DeepgramPocSession.createForTest(socket, limits, ignored -> {});

        byte[] first = {1};
        byte[] second = {2};
        CompletableFuture<Void> firstResult = session.sendAudio(first);
        CompletableFuture<Void> secondResult = session.sendAudio(second);
        first[0] = 9;
        second[0] = 9;

        firstSend.complete(socket);
        secondSend.complete(socket);

        ArgumentCaptor<ByteBuffer> frames = ArgumentCaptor.forClass(ByteBuffer.class);
        verify(socket, times(2)).sendBinary(frames.capture(), org.mockito.ArgumentMatchers.eq(true));
        assertThat(bytes(frames.getAllValues())).containsExactly(new byte[] {1}, new byte[] {2});
        assertThat(firstResult).isCompleted();
        assertThat(secondResult).isCompleted();
        session.close();
    }

    @Test
    void boundsPendingMessageCountWithoutBlockingCaller() throws Exception {
        WebSocket socket = mock(WebSocket.class);
        CompletableFuture<WebSocket> stalledSend = new CompletableFuture<>();
        when(socket.sendBinary(any(ByteBuffer.class), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(stalledSend);
        DeepgramPocSession session = DeepgramPocSession.createForTest(socket, limits, ignored -> {});
        List<CompletableFuture<Void>> accepted = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            accepted.add(session.sendAudio(new byte[] {(byte) index}));
        }

        CompletableFuture<Void> rejected = session.sendAudio(new byte[] {33});

        assertThatThrownBy(rejected::join)
                .hasRootCauseMessage("Deepgram send queue is full")
                .hasMessageNotContaining("33");
        assertThat(session.completion().get(1, TimeUnit.SECONDS))
                .isEqualTo(DeepgramPocSession.StopReason.BACKPRESSURE);
        assertThat(accepted).allMatch(CompletableFuture::isCompletedExceptionally);
        verify(socket, times(1)).sendBinary(any(ByteBuffer.class), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void surfacesSanitizedProviderErrorAndContinuesUntilNormalClose() throws Exception {
        WebSocket socket = mock(WebSocket.class);
        when(socket.sendText(any(CharSequence.class), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(CompletableFuture.completedFuture(socket));
        List<DeepgramMessage> received = new ArrayList<>();
        DeepgramPocSession session = DeepgramPocSession.createForTest(socket, limits, received::add);

        session.onText(socket,
                "{\"type\":\"Error\",\"err_code\":\"SECRET\",\"description\":\"raw payload\"}", true);
        session.onText(socket,
                "{\"type\":\"Results\",\"channel_index\":[0,1],\"start\":0,\"duration\":1,"
                        + "\"is_final\":true,\"speech_final\":true,"
                        + "\"channel\":{\"alternatives\":[{\"transcript\":\"after error\",\"words\":[]}]}}",
                true);

        assertThat(received).containsExactly(
                new DeepgramMessage.Error("DEEPGRAM_ERROR"),
                new DeepgramMessage.Results(List.of(0, 1), 0, 1_000, "after error",
                        true, true, false, List.of()));
        assertThat(session.completion()).isNotDone();

        session.finish();
        session.onText(socket, "{\"type\":\"Metadata\",\"request_id\":\"final\"}", true);
        session.onClose(socket, WebSocket.NORMAL_CLOSURE, "provider details");
        assertThat(session.completion().get(1, TimeUnit.SECONDS))
                .isEqualTo(DeepgramPocSession.StopReason.COMPLETED);
    }

    @Test
    void callerCannotCancelInternalCompletionOrFinishState() throws Exception {
        WebSocket socket = mock(WebSocket.class);
        when(socket.sendText(any(CharSequence.class), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(CompletableFuture.completedFuture(socket));
        DeepgramPocSession session = DeepgramPocSession.createForTest(socket, limits, ignored -> {});

        CompletableFuture<DeepgramPocSession.StopReason> exposedCompletion = session.completion();
        assertThat(exposedCompletion.cancel(true)).isTrue();
        CompletableFuture<Void> exposedFinish = session.finish();
        assertThat(exposedFinish.cancel(true)).isTrue();
        session.onText(socket, "{\"type\":\"Metadata\",\"request_id\":\"final\"}", true);
        session.onClose(socket, WebSocket.NORMAL_CLOSURE, "raw provider text");

        assertThat(session.completion().get(1, TimeUnit.SECONDS))
                .isEqualTo(DeepgramPocSession.StopReason.COMPLETED);
        assertThat(session.finish().get(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void finalizationTimeoutStartsOnlyAfterQueuedAudioAndCloseStreamAreSent() throws Exception {
        WebSocket socket = mock(WebSocket.class);
        CompletableFuture<WebSocket> stalledAudio = new CompletableFuture<>();
        when(socket.sendBinary(any(ByteBuffer.class), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(stalledAudio);
        when(socket.sendText(any(CharSequence.class), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(CompletableFuture.completedFuture(socket));
        DeepgramPocSession session = DeepgramPocSession.createForTest(
                socket, limits, ignored -> {}, Duration.ofMillis(40));

        session.sendAudio(new byte[] {1});
        session.finish();
        assertThat(session.finalizationTimeoutScheduledForTest()).isFalse();
        assertThat(session.completion()).isNotDone();

        stalledAudio.complete(socket);
        assertThat(session.finalizationTimeoutScheduledForTest()).isTrue();
        assertThat(session.completion().get(1, TimeUnit.SECONDS))
                .isEqualTo(DeepgramPocSession.StopReason.FINALIZE_TIMEOUT);
    }

    @Test
    void normalCloseRequiresDispatchedCloseStreamAndFinalMetadata() throws Exception {
        WebSocket missingMetadataSocket = mock(WebSocket.class);
        when(missingMetadataSocket.sendText(any(CharSequence.class), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(CompletableFuture.completedFuture(missingMetadataSocket));
        DeepgramPocSession missingMetadata = DeepgramPocSession.createForTest(
                missingMetadataSocket, limits, ignored -> {});
        missingMetadata.finish();
        missingMetadata.onClose(missingMetadataSocket, WebSocket.NORMAL_CLOSURE, "provider details");
        assertThat(missingMetadata.completion().get(1, TimeUnit.SECONDS))
                .isEqualTo(DeepgramPocSession.StopReason.PROVIDER_ERROR);

        WebSocket notDispatchedSocket = mock(WebSocket.class);
        CompletableFuture<WebSocket> stalledAudio = new CompletableFuture<>();
        when(notDispatchedSocket.sendBinary(any(ByteBuffer.class), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(stalledAudio);
        DeepgramPocSession notDispatched = DeepgramPocSession.createForTest(
                notDispatchedSocket, limits, ignored -> {});
        notDispatched.sendAudio(new byte[] {1});
        notDispatched.finish();
        notDispatched.onText(notDispatchedSocket,
                "{\"type\":\"Metadata\",\"request_id\":\"too-early\"}", true);
        notDispatched.onClose(notDispatchedSocket, WebSocket.NORMAL_CLOSURE, "provider details");
        assertThat(notDispatched.completion().get(1, TimeUnit.SECONDS))
                .isEqualTo(DeepgramPocSession.StopReason.PROVIDER_ERROR);
    }

    @Test
    void goingAwayCloseIsNotACompletedFinalization() throws Exception {
        WebSocket socket = mock(WebSocket.class);
        when(socket.sendText(any(CharSequence.class), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(CompletableFuture.completedFuture(socket));
        DeepgramPocSession session = DeepgramPocSession.createForTest(socket, limits, ignored -> {});
        session.finish();
        session.onText(socket, "{\"type\":\"Metadata\",\"request_id\":\"final\"}", true);

        session.onClose(socket, 1001, "provider details");

        assertThat(session.completion().get(1, TimeUnit.SECONDS))
                .isEqualTo(DeepgramPocSession.StopReason.PROVIDER_ERROR);
    }

    @Test
    void rejectsOversizedFramesBeforeTheyReachTheSocket() {
        WebSocket socket = mock(WebSocket.class);
        DeepgramPocSession session = DeepgramPocSession.createForTest(socket, limits, ignored -> {});

        CompletableFuture<Void> result = session.sendAudio(new byte[1_048_577]);

        assertThat(result).isCompletedExceptionally();
        assertThat(session.completion()).isCompletedWithValue(DeepgramPocSession.StopReason.BACKPRESSURE);
        verify(socket, times(0)).sendBinary(any(ByteBuffer.class), org.mockito.ArgumentMatchers.eq(true));
    }

    private List<byte[]> bytes(List<ByteBuffer> buffers) {
        return buffers.stream().map(buffer -> {
            byte[] value = new byte[buffer.remaining()];
            buffer.get(value);
            return value;
        }).toList();
    }
}
