package dev.aimeeting.api.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.provider.SpeechEvent;
import dev.aimeeting.api.provider.SpeechProvider;
import dev.aimeeting.api.provider.deepgram.DeepgramLiveSessionFactory;
import dev.aimeeting.api.provider.deepgram.DeepgramMeetingPoc;
import dev.aimeeting.api.auth.SessionService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class LiveWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(LiveWebSocketHandler.class);
    private final MeetingRepository meetings;
    private final SpeechProvider provider;
    private final DeepgramLiveSessionFactory deepgram;
    private final ObjectMapper mapper;
    private final SessionService sessions;
    private final ConcurrentHashMap<String, Set<WebSocketSession>> openByAuthSession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WebSocketSession, LiveContext> liveContexts = new ConcurrentHashMap<>();

    @Autowired
    public LiveWebSocketHandler(MeetingRepository meetings, SpeechProvider provider, DeepgramLiveSessionFactory deepgram,
                                ObjectMapper mapper, SessionService sessions) {
        this.meetings = meetings;
        this.provider = provider;
        this.deepgram = deepgram;
        this.mapper = mapper;
        this.sessions = sessions;
        sessions.onInvalidation(this::closeAuthenticatedSockets);
        sessions.onAllInvalidation(this::closeAllAuthenticatedSockets);
    }

    LiveWebSocketHandler(MeetingRepository meetings, SpeechProvider provider, ObjectMapper mapper,
                         SessionService sessions) {
        this(meetings, provider, null, mapper, sessions);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.withLifecycleGuard(() -> {
            synchronized (session) {
                String authSessionId = (String) session.getAttributes().get("authSessionId");
                if (authSessionId == null) {
                    session.close(CloseStatus.POLICY_VIOLATION.withReason("Authentication expired"));
                    return;
                }
                openByAuthSession.computeIfAbsent(authSessionId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
                if (!sessions.isValid(authSessionId)) {
                    closeAuthenticatedSockets(authSessionId);
                }
            }
        });
        String authSessionId = (String) session.getAttributes().get("authSessionId");
        if (!isRegistered(authSessionId, session) || !sessions.isValid(authSessionId)) return;
        UUID meetingId = meetingId(session);
        Meeting meeting = meetings.find(meetingId);
        if (!"STARTED".equals(meeting.status())) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Meeting must be started first"));
            unregister(session, authSessionId);
            return;
        }
        if ("LIVE".equals(meeting.inputSource())) {
            openDeepgram(session, authSessionId, meetingId, audioOffsetMs(session));
            return;
        }
        if ("LOCAL_RECORDING".equals(meeting.inputSource())) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Live streaming is unavailable for local recordings"));
            unregister(session, authSessionId);
            return;
        }
        UUID streamSessionId = meetings.createStreamSession(meetingId);
        LiveContext context = new LiveContext(meetingId, streamSessionId, false);
        liveContexts.put(session, context);
        if (!isRegistered(authSessionId, session) || !sessions.isValid(authSessionId)) {
            terminate(session, "SESSION_LOGOUT");
            return;
        }
        provider.stream(meetingId, streamSessionId, event -> sendForContext(session, context, event));
    }

    private void openDeepgram(WebSocketSession session, String authSessionId, UUID meetingId, long audioOffsetMs) {
        if (deepgram == null) {
            failStream(session, new LiveContext(meetingId, null, true), "Live speech is unavailable");
            return;
        }
        LiveContext context = new LiveContext(meetingId, null, true);
        liveContexts.put(session, context);
        if (!isRegistered(authSessionId, session) || !sessions.isValid(authSessionId)) {
            liveContexts.remove(session, context);
            return;
        }
        CompletableFuture<DeepgramMeetingPoc> opening;
        try {
            opening = deepgram.open(meetingId, audioOffsetMs, event -> sendForContext(session, context, event));
        } catch (RuntimeException failure) {
            liveContexts.remove(session, context);
            failStream(session, context, speechFailure(failure));
            return;
        }
        synchronized (session) {
            context.opening = opening;
            if (liveContexts.get(session) != context || !isRegistered(authSessionId, session)
                    || !sessions.isValid(authSessionId)) {
                opening.cancel(true);
                return;
            }
        }
        opening.whenComplete((live, failure) -> {
            if (failure != null) {
                if (liveContexts.remove(session, context)) {
                    failStream(session, context, speechFailure(failure));
                }
                return;
            }
            synchronized (session) {
                if (liveContexts.get(session) != context || !isRegistered(authSessionId, session)
                        || !sessions.isValid(authSessionId)) {
                    live.close();
                    return;
                }
                context.live = live;
                context.streamSessionId = live.streamSessionId();
                sendForContext(session, context, SpeechEvent.sessionReady(meetingId, live.streamSessionId()));
            }
            live.completion().whenComplete((reason, completionFailure) -> {
                if (liveContexts.remove(session, context)) {
                    send(session, new SpeechEvent(completionFailure == null ? "stream.stopped" : "stream.error",
                            meetingId, live.streamSessionId(), null, null, null, null, null, null, null,
                            null, null, null, null, completionFailure == null ? reason.name() : "PROVIDER_ERROR"));
                }
            });
        });
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        LiveContext context = liveContexts.get(session);
        if (context == null || !context.deepgram || context.live == null) {
            closeWithError(session, "Speech session is not ready");
            return;
        }
        ByteBuffer payload = message.getPayload();
        byte[] pcm = new byte[payload.remaining()];
        payload.get(pcm);
        context.live.sendAudio(pcm).whenComplete((ignored, failure) -> {
            if (failure != null && liveContexts.remove(session, context)) {
                context.live.close();
                failStream(session, context, "Speech audio was rejected");
            }
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        LiveContext context = liveContexts.get(session);
        if (context == null || !context.deepgram || context.live == null) return;
        try {
            Map<?, ?> control = mapper.readValue(message.getPayload(), Map.class);
            if ("stream.stop".equals(control.get("type"))) context.live.finish();
            else if ("stream.ping".equals(control.get("type"))) context.live.keepAlive();
        } catch (IOException ignored) {
            closeWithError(session, "Invalid stream control message");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        synchronized (session) {
            terminate(session, "SOCKET_CLOSED");
            String authSessionId = (String) session.getAttributes().get("authSessionId");
            unregister(session, authSessionId);
        }
    }

    private void closeAuthenticatedSockets(String authSessionId) {
        Set<WebSocketSession> open = openByAuthSession.remove(authSessionId);
        if (open == null) return;
        for (WebSocketSession session : open) {
            synchronized (session) {
                try {
                    terminate(session, "SESSION_LOGOUT");
                } catch (RuntimeException failure) {
                    log.warn("Authenticated WebSocket cleanup failed: operation=session invalidation, type={}",
                            failure.getClass().getSimpleName());
                } finally {
                    try {
                        if (session.isOpen()) session.close(CloseStatus.POLICY_VIOLATION.withReason("Session logged out"));
                    } catch (IOException ignored) {
                        // The connection is already disappearing.
                    }
                }
            }
        }
    }

    private boolean isRegistered(String authSessionId, WebSocketSession session) {
        Set<WebSocketSession> open = openByAuthSession.get(authSessionId);
        return open != null && open.contains(session);
    }

    private void closeAllAuthenticatedSockets() {
        for (String authSessionId : openByAuthSession.keySet()) closeAuthenticatedSockets(authSessionId);
    }

    @Scheduled(fixedDelayString = "${app.session-validation-interval-ms:30000}")
    void validateOpenSessions() {
        for (String authSessionId : openByAuthSession.keySet()) {
            if (!sessions.isValid(authSessionId)) closeAuthenticatedSockets(authSessionId);
        }
    }

    public void stopMeeting(UUID meetingId) {
        provider.stopMeeting(meetingId);
        liveContexts.forEach((session, context) -> {
            if (!context.meetingId.equals(meetingId)) return;
            liveContexts.remove(session, context);
            if (context.opening != null && !context.opening.isDone()) context.opening.cancel(true);
            if (context.live != null) context.live.close();
            send(session, new SpeechEvent("stream.stopped", meetingId, context.streamSessionId, null, null,
                    null, null, null, null, null, null, null, null, null, "STOPPED_BY_USER"));
        });
    }

    private void terminate(WebSocketSession session, String reason) {
        LiveContext context = liveContexts.remove(session);
        if (context == null) return;
        if (context.opening != null && !context.opening.isDone()) context.opening.cancel(true);
        if (context.deepgram) {
            if (context.live != null) {
                // A disappearing browser socket is not a provider failure. Ask Deepgram to flush final metadata so
                // the provider-owned cleanup can settle measured audio and release the active claim. Logout and an
                // explicit meeting stop remain immediate cancellation paths.
                if ("SOCKET_CLOSED".equals(reason)) context.live.finish();
                else context.live.close();
            }
            if ("SESSION_LOGOUT".equals(reason)) stopMeetingIfStarted(context.meetingId);
            return;
        }
        boolean anotherSocketExists = liveContexts.values().stream()
                .anyMatch(other -> other.meetingId.equals(context.meetingId));
        if (anotherSocketExists) {
            runCleanup("provider session stop", () -> provider.stop(context.streamSessionId));
            runCleanup("stream session close", () -> meetings.closeStreamSession(context.streamSessionId, reason));
            return;
        }
        runCleanup("provider meeting stop", () -> provider.stopMeeting(context.meetingId));
        runCleanup("stream session close", () -> meetings.closeStreamSession(context.streamSessionId, reason));
        stopMeetingIfStarted(context.meetingId);
    }

    private void stopMeetingIfStarted(UUID meetingId) {
        try {
            if ("STARTED".equals(meetings.find(meetingId).status())) {
                runCleanup("meeting stop", () -> meetings.stop(meetingId));
            }
        } catch (RuntimeException failure) {
            log.warn("Authenticated WebSocket cleanup failed: operation=meeting lookup, type={}",
                    failure.getClass().getSimpleName());
        }
    }

    private void runCleanup(String operation, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException failure) {
            log.warn("Authenticated WebSocket cleanup failed: operation={}, type={}",
                    operation, failure.getClass().getSimpleName());
        }
    }

    private void send(WebSocketSession session, SpeechEvent event) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(mapper.writeValueAsString(event)));
                    if ("stream.stopped".equals(event.type())) session.close(CloseStatus.NORMAL);
                }
            }
        } catch (IOException ignored) {
            // A disconnected browser is expected; no transcript content is logged.
        }
    }

    private void sendForContext(WebSocketSession session, LiveContext context, SpeechEvent event) {
        if (liveContexts.get(session) == context) send(session, event);
    }

    private void closeWithError(WebSocketSession session, String reason) {
        try {
            synchronized (session) {
                if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR.withReason(reason));
            }
        } catch (IOException ignored) {
            // The browser has already disconnected.
        }
    }

    private String speechFailure(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        // Only known failures are mapped. Never expose raw provider responses or credentials.
        String detail = failure.getMessage();
        if ("MONTHLY_BUDGET_EXHAUSTED".equals(detail)) return "월 예산이 부족합니다. 사용량·예산에서 예산을 저장한 뒤 AI 전사 다시 연결을 누르세요.";
        if ("MEETING_BUDGET_EXHAUSTED".equals(detail)) return "회의당 예산을 모두 사용했습니다. 사용량·예산에서 회의당 한도를 확인하세요.";
        if (failure instanceof java.net.http.WebSocketHandshakeException handshake) {
            int status = handshake.getResponse().statusCode();
            if (status == 401 || status == 403) return "음성 인식 공급자 인증이 거절됐습니다. 서버의 API 키와 공급자 계정을 확인하세요.";
            if (status == 402 || status == 429) return "음성 인식 공급자의 잔액 또는 요청 한도를 확인하세요.";
        }
        if ("Meeting audio duration or budget limit reached".equals(detail)) return "회의 시간 또는 예산 한도에 도달했습니다. 사용량·예산 설정을 확인하세요.";
        log.warn("Live speech connection failed: type={}", failure.getClass().getSimpleName());
        return "AI 전사에 연결하지 못했습니다. 서버 AI 설정과 공급자 연결을 확인한 뒤 다시 연결하세요. 원본 녹음은 계속됩니다.";
    }

    private void failStream(WebSocketSession session, LiveContext context, String reason) {
        send(session, new SpeechEvent("stream.error", context.meetingId, context.streamSessionId, null, null,
                null, null, null, null, null, null, null, null, null, "PROVIDER_ERROR", reason));
        closeWithError(session, "Live speech unavailable");
    }

    private void unregister(WebSocketSession session, String authSessionId) {
        Set<WebSocketSession> open = openByAuthSession.get(authSessionId);
        if (open == null) return;
        open.remove(session);
        if (open.isEmpty()) openByAuthSession.remove(authSessionId, open);
    }

    private UUID meetingId(WebSocketSession session) {
        String path = session.getUri().getPath();
        String value = path.substring("/api/v1/meetings/".length(), path.length() - "/live".length());
        return UUID.fromString(value);
    }

    private long audioOffsetMs(WebSocketSession session) {
        String query = session.getUri().getRawQuery();
        if (query == null) return 0;
        for (String pair : query.split("&")) {
            if (!pair.startsWith("audioOffsetMs=")) continue;
            long offset = Long.parseLong(pair.substring("audioOffsetMs=".length()));
            if (offset < 0) throw new IllegalArgumentException("audioOffsetMs must be nonnegative");
            return offset;
        }
        return 0;
    }

    private static final class LiveContext {
        private final UUID meetingId;
        private final boolean deepgram;
        private volatile UUID streamSessionId;
        private volatile CompletableFuture<DeepgramMeetingPoc> opening;
        private volatile DeepgramMeetingPoc live;

        private LiveContext(UUID meetingId, UUID streamSessionId, boolean deepgram) {
            this.meetingId = meetingId;
            this.streamSessionId = streamSessionId;
            this.deepgram = deepgram;
        }
    }
}
