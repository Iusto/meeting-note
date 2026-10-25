package dev.aimeeting.api.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.provider.SpeechEvent;
import dev.aimeeting.api.provider.SpeechProvider;
import dev.aimeeting.api.auth.SessionService;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class LiveWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(LiveWebSocketHandler.class);
    private final MeetingRepository meetings;
    private final SpeechProvider provider;
    private final ObjectMapper mapper;
    private final SessionService sessions;
    private final ConcurrentHashMap<String, Set<WebSocketSession>> openByAuthSession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WebSocketSession, LiveContext> liveContexts = new ConcurrentHashMap<>();

    public LiveWebSocketHandler(MeetingRepository meetings, SpeechProvider provider, ObjectMapper mapper, SessionService sessions) {
        this.meetings = meetings;
        this.provider = provider;
        this.mapper = mapper;
        this.sessions = sessions;
        sessions.onInvalidation(this::closeAuthenticatedSockets);
        sessions.onAllInvalidation(this::closeAllAuthenticatedSockets);
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
                    return;
                }
                UUID meetingId = meetingId(session);
                Meeting meeting = meetings.find(meetingId);
                if (!isRegistered(authSessionId, session) || !sessions.isValid(authSessionId)) return;
                if ("LOCAL_RECORDING".equals(meeting.inputSource())) {
                    session.close(CloseStatus.POLICY_VIOLATION.withReason("Live streaming is unavailable for local recordings"));
                    return;
                }
                if (!"STARTED".equals(meeting.status())) {
                    session.close(CloseStatus.POLICY_VIOLATION.withReason("Meeting must be started first"));
                    return;
                }
                UUID streamSessionId = meetings.createStreamSession(meetingId);
                liveContexts.put(session, new LiveContext(meetingId, streamSessionId));
                if (!isRegistered(authSessionId, session) || !sessions.isValid(authSessionId)) {
                    terminate(session, "SESSION_LOGOUT");
                    return;
                }
                provider.stream(meetingId, streamSessionId, event -> send(session, event));
            }
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        synchronized (session) {
            terminate(session, "SOCKET_CLOSED");
            String authSessionId = (String) session.getAttributes().get("authSessionId");
            Set<WebSocketSession> open = openByAuthSession.get(authSessionId);
            if (open != null) {
                open.remove(session);
                if (open.isEmpty()) openByAuthSession.remove(authSessionId, open);
            }
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
            if (!context.meetingId().equals(meetingId)) return;
            liveContexts.remove(session, context);
            meetings.closeStreamSession(context.streamSessionId(), "MEETING_STOPPED");
            send(session, new SpeechEvent("stream.stopped", meetingId, context.streamSessionId(), null, null,
                    null, null, null, null, null, null, null, null, null, "STOPPED_BY_USER"));
        });
    }

    private void terminate(WebSocketSession session, String reason) {
        LiveContext context = liveContexts.remove(session);
        if (context == null) return;
        boolean anotherSocketExists = liveContexts.values().stream()
                .anyMatch(other -> other.meetingId().equals(context.meetingId()));
        if (anotherSocketExists) {
            runCleanup("provider session stop", () -> provider.stop(context.streamSessionId()));
            runCleanup("stream session close", () -> meetings.closeStreamSession(context.streamSessionId(), reason));
            return;
        }
        runCleanup("provider meeting stop", () -> provider.stopMeeting(context.meetingId()));
        runCleanup("stream session close", () -> meetings.closeStreamSession(context.streamSessionId(), reason));
        try {
            if ("STARTED".equals(meetings.find(context.meetingId()).status())) {
                runCleanup("meeting stop", () -> meetings.stop(context.meetingId()));
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

    private UUID meetingId(WebSocketSession session) {
        String path = session.getUri().getPath();
        String value = path.substring("/api/v1/meetings/".length(), path.length() - "/live".length());
        return UUID.fromString(value);
    }

    private record LiveContext(UUID meetingId, UUID streamSessionId) {}
}
