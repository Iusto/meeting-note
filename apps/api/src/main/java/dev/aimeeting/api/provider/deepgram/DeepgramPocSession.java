package dev.aimeeting.api.provider.deepgram;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** A deliberately small, bounded Deepgram Live POC transport. */
public final class DeepgramPocSession implements WebSocket.Listener, AutoCloseable {
    private static final URI LIVE_ENDPOINT = URI.create("wss://api.deepgram.com/v1/listen");
    private static final String OFFLINE_AUTHORIZATION = "Token offline-test-token";
    private static final int MAX_AUDIO_FRAME_BYTES = 1_048_576;
    private static final int MAX_PENDING_AUDIO_BYTES = 4 * 1_048_576;
    private static final int MAX_PENDING_MESSAGES = 32;
    private static final int MAX_INCOMING_TEXT_BYTES = 256 * 1024;
    private static final Duration KEEPALIVE_INTERVAL = Duration.ofSeconds(3);
    private static final Duration FINALIZE_TIMEOUT = Duration.ofSeconds(5);
    private static final String KEEPALIVE = "{\"type\":\"KeepAlive\"}";
    private static final String CLOSE_STREAM = "{\"type\":\"CloseStream\"}";

    public enum StopReason {
        COMPLETED,
        USER_STOP,
        OWNERSHIP_LOST,
        TIME_LIMIT,
        CONNECT_FAILED,
        SEND_FAILED,
        PROVIDER_ERROR,
        INVALID_MESSAGE,
        BACKPRESSURE,
        FINALIZE_TIMEOUT,
        CONSUMER_FAILED
    }

    private final Object lock = new Object();
    private final DeepgramMessageParser parser = new DeepgramMessageParser(new ObjectMapper());
    private final Consumer<DeepgramMessage> onMessage;
    private final HttpClient httpClient;
    private final ExecutorService httpExecutor;
    private final ScheduledExecutorService scheduler;
    private final Duration finalizeTimeout;
    private final ArrayDeque<Outbound> outbound = new ArrayDeque<>();
    private final CompletableFuture<StopReason> completion = new CompletableFuture<>();
    private final CompletableFuture<Void> finish = new CompletableFuture<>();
    private final StringBuilder incomingText = new StringBuilder();

    private volatile WebSocket socket;
    private volatile CompletableFuture<WebSocket> handshake;
    private ScheduledFuture<?> deadlineTask;
    private ScheduledFuture<?> keepaliveTask;
    private ScheduledFuture<?> finalizeTask;
    private int incomingTextBytes;
    private long pendingAudioBytes;
    private int pendingMessages;
    private boolean sending;
    private Outbound inFlight;
    private boolean finishing;
    private boolean terminated;
    private boolean closeStreamDispatched;
    private boolean finalMetadataReceived;
    private long lastOutboundNanos;

    private DeepgramPocSession(PocLimits limits, Consumer<DeepgramMessage> onMessage,
                               HttpClient httpClient, ExecutorService httpExecutor,
                               ScheduledExecutorService scheduler, Duration finalizeTimeout) {
        this.onMessage = Objects.requireNonNull(onMessage, "onMessage");
        this.httpClient = httpClient;
        this.httpExecutor = Objects.requireNonNull(httpExecutor, "httpExecutor");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.finalizeTimeout = Objects.requireNonNull(finalizeTimeout, "finalizeTimeout");
        long deadlineNanos = durationNanosCeiling(limits.maxDuration());
        this.deadlineTask = scheduler.schedule(() -> terminate(StopReason.TIME_LIMIT),
                deadlineNanos, TimeUnit.NANOSECONDS);
    }

    public static CompletableFuture<DeepgramPocSession> connect(
            boolean paidOptIn,
            String apiKey,
            DeepgramOptions options,
            PocLimits limits,
            Consumer<DeepgramMessage> onMessage) {
        if (!paidOptIn) {
            throw new IllegalStateException("Paid Deepgram POC access is disabled");
        }
        validateApiKey(apiKey);
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(onMessage, "onMessage");
        URI endpoint = withQuery(LIVE_ENDPOINT, options.queryString());
        return open(endpoint, "Token " + apiKey, limits, onMessage);
    }

    static CompletableFuture<DeepgramPocSession> connectLoopback(
            URI endpoint,
            DeepgramOptions options,
            PocLimits limits,
            Consumer<DeepgramMessage> onMessage) {
        validateLoopbackEndpoint(endpoint);
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(onMessage, "onMessage");
        return open(withQuery(endpoint, options.queryString()), OFFLINE_AUTHORIZATION, limits, onMessage);
    }

    static DeepgramPocSession createForTest(
            WebSocket socket, PocLimits limits, Consumer<DeepgramMessage> onMessage) {
        Objects.requireNonNull(socket, "socket");
        DeepgramPocSession session = new DeepgramPocSession(limits, onMessage,
                null, Executors.newVirtualThreadPerTaskExecutor(), newScheduler(), FINALIZE_TIMEOUT);
        session.connected(socket);
        return session;
    }

    static DeepgramPocSession createForTest(
            WebSocket socket, PocLimits limits, Consumer<DeepgramMessage> onMessage, Duration finalizeTimeout) {
        Objects.requireNonNull(socket, "socket");
        DeepgramPocSession session = new DeepgramPocSession(limits, onMessage,
                null, Executors.newVirtualThreadPerTaskExecutor(), newScheduler(), finalizeTimeout);
        session.connected(socket);
        return session;
    }

    private static CompletableFuture<DeepgramPocSession> open(
            URI endpoint, String authorization, PocLimits limits, Consumer<DeepgramMessage> onMessage) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        ScheduledExecutorService scheduler = newScheduler();
        HttpClient client;
        try {
            client = HttpClient.newBuilder().executor(executor).build();
        } catch (RuntimeException exception) {
            scheduler.shutdownNow();
            executor.shutdownNow();
            return CompletableFuture.failedFuture(sanitized("Deepgram connection failed"));
        }
        DeepgramPocSession session = new DeepgramPocSession(
                limits, onMessage, client, executor, scheduler, FINALIZE_TIMEOUT);
        CompletableFuture<WebSocket> opening;
        try {
            opening = client.newWebSocketBuilder()
                    .header("Authorization", authorization)
                    .buildAsync(endpoint, session);
        } catch (RuntimeException exception) {
            session.terminate(StopReason.CONNECT_FAILED);
            return CompletableFuture.failedFuture(sanitized("Deepgram connection failed"));
        }
        CompletableFuture<DeepgramPocSession> result = new CompletableFuture<>();
        synchronized (session.lock) {
            session.handshake = opening;
            if (session.terminated) {
                opening.cancel(true);
            }
        }
        session.completion.thenAccept(reason -> result.completeExceptionally(
                sanitized(reason == StopReason.TIME_LIMIT
                        ? "Deepgram connection did not complete in time"
                        : "Deepgram connection failed")));
        opening.whenComplete((webSocket, failure) -> {
            if (failure != null) {
                session.terminate(StopReason.CONNECT_FAILED);
                result.completeExceptionally(sanitized("Deepgram connection failed"));
                return;
            }
            if (session.completion.isDone()) {
                webSocket.abort();
                result.completeExceptionally(sanitized("Deepgram connection did not complete in time"));
                return;
            }
            session.connected(webSocket);
            result.complete(session);
        });
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                session.terminate(StopReason.USER_STOP);
            }
        });
        return result;
    }

    public CompletableFuture<Void> sendAudio(byte[] audio) {
        Objects.requireNonNull(audio, "audio");
        if (audio.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        if (audio.length > MAX_AUDIO_FRAME_BYTES) {
            terminate(StopReason.BACKPRESSURE);
            return CompletableFuture.failedFuture(sanitized("Audio frame exceeds the POC limit"));
        }
        byte[] copy = audio.clone();
        CompletableFuture<Void> sent = new CompletableFuture<>();
        boolean overflow = false;
        synchronized (lock) {
            if (terminated || finishing) {
                return CompletableFuture.failedFuture(sanitized("Deepgram session is not accepting audio"));
            }
            if (pendingMessages >= MAX_PENDING_MESSAGES
                    || pendingAudioBytes + copy.length > MAX_PENDING_AUDIO_BYTES) {
                overflow = true;
            } else {
                outbound.addLast(Outbound.audio(copy, sent));
                pendingMessages++;
                pendingAudioBytes += copy.length;
            }
        }
        if (overflow) {
            terminate(StopReason.BACKPRESSURE);
            sent.completeExceptionally(sanitized("Deepgram send queue is full"));
            return sent;
        }
        drain();
        return sent;
    }

    public CompletableFuture<Void> finish() {
        boolean enqueue = false;
        synchronized (lock) {
            if (terminated) {
                completeFinishFromStopReason();
                return finish.copy();
            }
            if (!finishing) {
                finishing = true;
                enqueue = true;
                outbound.addLast(Outbound.control(CLOSE_STREAM));
            }
        }
        if (enqueue) {
            drain();
        }
        return finish.copy();
    }

    public CompletableFuture<StopReason> completion() {
        return completion.copy();
    }

    boolean finalizationTimeoutScheduledForTest() {
        synchronized (lock) {
            return finalizeTask != null;
        }
    }

    @Override
    public void close() {
        terminate(StopReason.USER_STOP);
    }

    void ownershipLost() {
        terminate(StopReason.OWNERSHIP_LOST);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        if (completion.isDone()) {
            return CompletableFuture.completedFuture(null);
        }
        int remainingBytes;
        synchronized (lock) {
            remainingBytes = MAX_INCOMING_TEXT_BYTES - incomingTextBytes;
        }
        if (data.length() > remainingBytes) {
            terminate(StopReason.INVALID_MESSAGE);
            return CompletableFuture.completedFuture(null);
        }
        int fragmentBytes = data.toString().getBytes(StandardCharsets.UTF_8).length;
        String message = null;
        boolean oversized = false;
        synchronized (lock) {
            if (terminated) {
                return CompletableFuture.completedFuture(null);
            }
            if (fragmentBytes > MAX_INCOMING_TEXT_BYTES - incomingTextBytes) {
                oversized = true;
            } else {
                incomingText.append(data);
                incomingTextBytes += fragmentBytes;
                if (last) {
                    message = incomingText.toString();
                    incomingText.setLength(0);
                    incomingTextBytes = 0;
                }
            }
        }
        if (oversized) {
            terminate(StopReason.INVALID_MESSAGE);
            return CompletableFuture.completedFuture(null);
        }
        if (message != null) {
            acceptMessage(message);
        }
        if (!completion.isDone()) {
            webSocket.request(1);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        terminate(StopReason.INVALID_MESSAGE);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
        webSocket.request(1);
        return WebSocket.Listener.super.onPing(webSocket, message);
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        StopReason stopReason;
        synchronized (lock) {
            stopReason = finishing && closeStreamDispatched && finalMetadataReceived
                    && statusCode == WebSocket.NORMAL_CLOSURE
                    ? StopReason.COMPLETED : StopReason.PROVIDER_ERROR;
        }
        terminate(stopReason);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        terminate(StopReason.PROVIDER_ERROR);
    }

    private void connected(WebSocket connectedSocket) {
        synchronized (lock) {
            if (terminated) {
                connectedSocket.abort();
                return;
            }
            socket = connectedSocket;
            lastOutboundNanos = System.nanoTime();
            keepaliveTask = scheduler.scheduleAtFixedRate(this::sendKeepaliveIfIdle,
                    KEEPALIVE_INTERVAL.toNanos(), KEEPALIVE_INTERVAL.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    private void sendKeepaliveIfIdle() {
        synchronized (lock) {
            if (terminated || finishing || sending || !outbound.isEmpty() || socket == null) {
                return;
            }
            if (System.nanoTime() - lastOutboundNanos < KEEPALIVE_INTERVAL.toNanos()) {
                return;
            }
            outbound.addLast(Outbound.control(KEEPALIVE));
        }
        drain();
    }

    private void drain() {
        Outbound next;
        WebSocket webSocket;
        synchronized (lock) {
            if (terminated || sending || socket == null || outbound.isEmpty()) {
                return;
            }
            sending = true;
            next = outbound.removeFirst();
            inFlight = next;
            if (CLOSE_STREAM.equals(next.control)) {
                closeStreamDispatched = true;
            }
            webSocket = socket;
            lastOutboundNanos = System.nanoTime();
        }
        CompletableFuture<WebSocket> operation;
        try {
            operation = next.audio == null
                    ? webSocket.sendText(next.control, true)
                    : webSocket.sendBinary(ByteBuffer.wrap(next.audio).asReadOnlyBuffer(), true);
        } catch (RuntimeException exception) {
            sent(next, false);
            terminate(StopReason.SEND_FAILED);
            return;
        }
        operation.whenComplete((ignored, failure) -> {
            sent(next, failure == null);
            if (failure != null) {
                terminate(StopReason.SEND_FAILED);
            } else {
                drain();
            }
        });
    }

    private void sent(Outbound sent, boolean success) {
        synchronized (lock) {
            sending = false;
            inFlight = null;
            if (!terminated && sent.audio != null) {
                pendingMessages--;
                pendingAudioBytes -= sent.audio.length;
            }
            if (!terminated && success && CLOSE_STREAM.equals(sent.control)) {
                finalizeTask = scheduler.schedule(() -> terminate(StopReason.FINALIZE_TIMEOUT),
                        finalizeTimeout.toNanos(), TimeUnit.NANOSECONDS);
            }
        }
        if (sent.result != null) {
            if (success) {
                sent.result.complete(null);
            } else {
                sent.result.completeExceptionally(sanitized("Deepgram audio send failed"));
            }
        }
    }

    private void acceptMessage(String json) {
        DeepgramMessage parsed;
        try {
            parsed = parser.parse(json, 0);
        } catch (RuntimeException exception) {
            terminate(StopReason.INVALID_MESSAGE);
            return;
        }
        try {
            onMessage.accept(parsed);
        } catch (RuntimeException exception) {
            terminate(StopReason.CONSUMER_FAILED);
            return;
        }
        if (parsed instanceof DeepgramMessage.Metadata) {
            synchronized (lock) {
                if (closeStreamDispatched && !terminated) {
                    finalMetadataReceived = true;
                }
            }
        }
    }

    private void terminate(StopReason reason) {
        WebSocket webSocket;
        CompletableFuture<WebSocket> opening;
        ArrayDeque<Outbound> abandoned = new ArrayDeque<>();
        synchronized (lock) {
            if (terminated) {
                return;
            }
            terminated = true;
            webSocket = socket;
            opening = handshake;
            if (inFlight != null) {
                abandoned.add(inFlight);
                inFlight = null;
            }
            abandoned.addAll(outbound);
            outbound.clear();
            pendingMessages = 0;
            pendingAudioBytes = 0;
            incomingText.setLength(0);
            incomingTextBytes = 0;
            cancel(deadlineTask);
            cancel(keepaliveTask);
            cancel(finalizeTask);
        }
        if (opening != null && !opening.isDone()) {
            attempt(() -> opening.cancel(true));
        }
        if (webSocket != null) {
            attempt(webSocket::abort);
        }
        for (Outbound pending : abandoned) {
            if (pending.result != null) {
                attempt(() -> pending.result.completeExceptionally(sanitized("Deepgram session stopped")));
            }
        }
        completion.complete(reason);
        completeFinishFromStopReason();
        if (httpClient != null) {
            attempt(httpClient::shutdownNow);
        }
        attempt(scheduler::shutdownNow);
        attempt(httpExecutor::shutdownNow);
    }

    private void completeFinishFromStopReason() {
        StopReason reason = completion.getNow(null);
        if (reason == StopReason.COMPLETED) {
            finish.complete(null);
        } else if (reason != null) {
            finish.completeExceptionally(sanitized("Deepgram finalization did not complete"));
        }
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            attempt(() -> task.cancel(false));
        }
    }

    private static void attempt(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // Every teardown action is best-effort; one broken resource must not block the remaining cleanup.
        }
    }

    private static URI withQuery(URI endpoint, String query) {
        try {
            return URI.create(endpoint.toASCIIString() + "?" + query);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid Deepgram endpoint");
        }
    }

    private static void validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()
                || apiKey.codePoints().anyMatch(codePoint -> Character.isWhitespace(codePoint)
                        || Character.isISOControl(codePoint))) {
            throw new IllegalArgumentException("Invalid Deepgram API key");
        }
    }

    static void validateLoopbackEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        String host = endpoint.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
        if (!"ws".equals(endpoint.getScheme()) || !loopback
                || endpoint.getPort() <= 0 || endpoint.getPort() > 65_535
                || !"/v1/listen".equals(endpoint.getRawPath()) || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Invalid offline Deepgram endpoint");
        }
    }

    private static long durationNanosCeiling(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Duration is too large");
        }
    }

    private static ScheduledExecutorService newScheduler() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "deepgram-poc-timer");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }

    private static IllegalStateException sanitized(String message) {
        return new IllegalStateException(message);
    }

    private static final class Outbound {
        private final byte[] audio;
        private final String control;
        private final CompletableFuture<Void> result;

        private Outbound(byte[] audio, String control, CompletableFuture<Void> result) {
            this.audio = audio;
            this.control = control;
            this.result = result;
        }

        private static Outbound audio(byte[] audio, CompletableFuture<Void> result) {
            return new Outbound(audio, null, result);
        }

        private static Outbound control(String control) {
            return new Outbound(null, control, null);
        }
    }
}
