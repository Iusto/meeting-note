package dev.aimeeting.api.live;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aimeeting.api.auth.SessionService;
import dev.aimeeting.api.config.AppProperties;
import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.provider.SpeechProvider;
import java.net.URI;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class LiveWebSocketLogoutTest {
    @Test
    void listenerFailureDoesNotUndoSessionRevocation() {
        AppProperties properties = new AppProperties("a-valid-owner-token-000000000000", List.of(), Path.of("target"), false, 100);
        SessionService auth = sessionService(properties);
        String authSessionId = auth.login("a-valid-owner-token-000000000000");
        auth.onInvalidation(ignored -> { throw new IllegalStateException("synthetic listener failure"); });

        auth.logout(authSessionId);

        if (auth.isValid(authSessionId)) throw new IllegalStateException("Logout was not committed");
    }

    @Test
    void logoutWaitsForProviderStartupAndThenStopsIt() throws Exception {
        AppProperties properties = new AppProperties("a-valid-owner-token-000000000000", List.of(), Path.of("target"), false, 100);
        SessionService auth = sessionService(properties);
        String authSessionId = auth.login("a-valid-owner-token-000000000000");
        UUID meetingId = UUID.randomUUID();
        MeetingRepository meetings = mock(MeetingRepository.class);
        SpeechProvider provider = mock(SpeechProvider.class);
        UUID streamSessionId = UUID.randomUUID();
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch continueProvider = new CountDownLatch(1);
        when(meetings.find(meetingId)).thenReturn(new Meeting(meetingId, "live", "STARTED", "MOCK", null, null, null, 1));
        when(meetings.createStreamSession(meetingId)).thenReturn(streamSessionId);
        doAnswer(ignored -> {
            providerStarted.countDown();
            if (!continueProvider.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test provider was not released");
            return null;
        }).when(provider).stream(eq(meetingId), eq(streamSessionId), any());
        LiveWebSocketHandler handler = new LiveWebSocketHandler(meetings, provider, new ObjectMapper(), auth);
        WebSocketSession socket = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put("authSessionId", authSessionId);
        when(socket.getAttributes()).thenReturn(attributes);
        when(socket.getUri()).thenReturn(URI.create("ws://localhost/api/v1/meetings/" + meetingId + "/live"));
        when(socket.isOpen()).thenReturn(true);

        CompletableFuture<Void> connection = CompletableFuture.runAsync(() -> {
            try {
                handler.afterConnectionEstablished(socket);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        if (!providerStarted.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Provider startup did not begin");
        CountDownLatch logoutTaskEntered = new CountDownLatch(1);
        AtomicReference<Thread> logoutThread = new AtomicReference<>();
        CompletableFuture<Void> logout = CompletableFuture.runAsync(() -> {
            logoutThread.set(Thread.currentThread());
            logoutTaskEntered.countDown();
            auth.logout(authSessionId);
        });
        if (!logoutTaskEntered.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Logout task did not start");
        long waiterDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (logoutThread.get().getState() != Thread.State.WAITING && System.nanoTime() < waiterDeadline) {
            Thread.onSpinWait();
        }
        if (logoutThread.get().getState() != Thread.State.WAITING) {
            throw new IllegalStateException("Logout did not wait on the lifecycle guard");
        }
        if (!auth.isValid(authSessionId)) throw new IllegalStateException("Session was revoked before provider startup completed");
        continueProvider.countDown();

        connection.get(5, TimeUnit.SECONDS);
        logout.get(5, TimeUnit.SECONDS);
        verify(provider).stream(eq(meetingId), eq(streamSessionId), any());
        verify(provider).stopMeeting(meetingId);
        verify(socket).close(any(CloseStatus.class));
    }

    @Test
    void cleanupFailureDoesNotUndoLogoutOrPreventSocketClosure() throws Exception {
        AppProperties properties = new AppProperties("a-valid-owner-token-000000000000", List.of(), Path.of("target"), false, 100);
        SessionService auth = sessionService(properties);
        String authSessionId = auth.login("a-valid-owner-token-000000000000");
        UUID meetingId = UUID.randomUUID();
        MeetingRepository meetings = mock(MeetingRepository.class);
        SpeechProvider provider = mock(SpeechProvider.class);
        UUID streamSessionId = UUID.randomUUID();
        when(meetings.find(meetingId)).thenReturn(new Meeting(meetingId, "live", "STARTED", "MOCK", null, null, null, 1));
        when(meetings.createStreamSession(meetingId)).thenReturn(streamSessionId);
        doThrow(new IllegalStateException("synthetic cleanup failure")).when(provider).stopMeeting(meetingId);
        LiveWebSocketHandler handler = new LiveWebSocketHandler(meetings, provider, new ObjectMapper(), auth);
        WebSocketSession socket = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put("authSessionId", authSessionId);
        when(socket.getAttributes()).thenReturn(attributes);
        when(socket.getUri()).thenReturn(URI.create("ws://localhost/api/v1/meetings/" + meetingId + "/live"));
        when(socket.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(socket);
        auth.logout(authSessionId);

        if (auth.isValid(authSessionId)) throw new IllegalStateException("Logout was not committed");
        verify(socket).close(any(CloseStatus.class));
        verify(meetings).closeStreamSession(streamSessionId, "SESSION_LOGOUT");
        verify(meetings).stop(meetingId);
    }

    @Test
    void logoutClosesSocketsCreatedByThatAuthenticationSession() throws Exception {
        AppProperties properties = new AppProperties("a-valid-owner-token-000000000000", List.of(), Path.of("target"), false, 100);
        SessionService auth = sessionService(properties);
        String authSessionId = auth.login("a-valid-owner-token-000000000000");
        UUID meetingId = UUID.randomUUID();
        MeetingRepository meetings = mock(MeetingRepository.class);
        SpeechProvider provider = mock(SpeechProvider.class);
        when(meetings.find(meetingId)).thenReturn(new Meeting(meetingId, "live", "STARTED", "MOCK", null, null, null, 1));
        when(meetings.createStreamSession(meetingId)).thenReturn(UUID.randomUUID());
        LiveWebSocketHandler handler = new LiveWebSocketHandler(meetings, provider, new ObjectMapper(), auth);
        WebSocketSession socket = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put("authSessionId", authSessionId);
        when(socket.getAttributes()).thenReturn(attributes);
        when(socket.getUri()).thenReturn(URI.create("ws://localhost/api/v1/meetings/" + meetingId + "/live"));
        when(socket.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(socket);
        auth.logout(authSessionId);

        verify(socket).close(any(CloseStatus.class));
        verify(provider).stopMeeting(meetingId);
        verify(meetings).stop(meetingId);
    }

    @Test
    void ownerSetupClosesSocketsCreatedByLegacyTokenSessions() throws Exception {
        String token = "a-valid-owner-token-000000000000";
        AppProperties properties = new AppProperties(token, List.of(), Path.of("target"), false, 100);
        SessionService auth = sessionService(properties);
        String authSessionId = auth.login(token);
        UUID meetingId = UUID.randomUUID();
        MeetingRepository meetings = mock(MeetingRepository.class);
        SpeechProvider provider = mock(SpeechProvider.class);
        when(meetings.find(meetingId)).thenReturn(new Meeting(meetingId, "live", "STARTED", "MOCK", null, null, null, 1));
        when(meetings.createStreamSession(meetingId)).thenReturn(UUID.randomUUID());
        LiveWebSocketHandler handler = new LiveWebSocketHandler(meetings, provider, new ObjectMapper(), auth);
        WebSocketSession socket = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put("authSessionId", authSessionId);
        when(socket.getAttributes()).thenReturn(attributes);
        when(socket.getUri()).thenReturn(URI.create("ws://localhost/api/v1/meetings/" + meetingId + "/live"));
        when(socket.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(socket);
        auth.setup(token, "owner", "a-secure-owner-password", false);

        verify(socket).close(any(CloseStatus.class));
        verify(provider).stopMeeting(meetingId);
        verify(meetings).stop(meetingId);
    }

    @Test
    void expiredAuthenticationSessionClosesItsOpenSockets() throws Exception {
        AppProperties properties = new AppProperties("a-valid-owner-token-000000000000", List.of(), Path.of("target"), false, 100);
        JdbcTemplate jdbc = authDatabase();
        SessionService auth = new SessionService(properties, jdbc);
        String authSessionId = auth.login("a-valid-owner-token-000000000000");
        UUID meetingId = UUID.randomUUID();
        MeetingRepository meetings = mock(MeetingRepository.class);
        SpeechProvider provider = mock(SpeechProvider.class);
        when(meetings.find(meetingId)).thenReturn(new Meeting(meetingId, "live", "STARTED", "MOCK", null, null, null, 1));
        when(meetings.createStreamSession(meetingId)).thenReturn(UUID.randomUUID());
        LiveWebSocketHandler handler = new LiveWebSocketHandler(meetings, provider, new ObjectMapper(), auth);
        WebSocketSession socket = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put("authSessionId", authSessionId);
        when(socket.getAttributes()).thenReturn(attributes);
        when(socket.getUri()).thenReturn(URI.create("ws://localhost/api/v1/meetings/" + meetingId + "/live"));
        when(socket.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(socket);
        jdbc.update("UPDATE auth_sessions SET expires_at=?", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        handler.validateOpenSessions();

        verify(socket).close(any(CloseStatus.class));
        verify(provider).stopMeeting(meetingId);
        verify(meetings).stop(meetingId);
    }

    @Test
    void localRecordingMeetingRejectsProviderWebSocketWithoutStoppingMeeting() throws Exception {
        AppProperties properties = new AppProperties("a-valid-owner-token-000000000000", List.of(), Path.of("target"), false, 100);
        SessionService auth = sessionService(properties);
        String authSessionId = auth.login("a-valid-owner-token-000000000000");
        UUID meetingId = UUID.randomUUID();
        MeetingRepository meetings = mock(MeetingRepository.class);
        SpeechProvider provider = mock(SpeechProvider.class);
        when(meetings.find(meetingId)).thenReturn(new Meeting(meetingId, "local", "STARTED", "LOCAL_RECORDING", null, null, null, 1));
        LiveWebSocketHandler handler = new LiveWebSocketHandler(meetings, provider, new ObjectMapper(), auth);
        WebSocketSession socket = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put("authSessionId", authSessionId);
        when(socket.getAttributes()).thenReturn(attributes);
        when(socket.getUri()).thenReturn(URI.create("ws://localhost/api/v1/meetings/" + meetingId + "/live"));

        handler.afterConnectionEstablished(socket);

        verify(socket).close(any(CloseStatus.class));
        verify(meetings, never()).createStreamSession(any());
        verifyNoInteractions(provider);
        verify(meetings, never()).stop(meetingId);
    }

    private SessionService sessionService(AppProperties properties) {
        return new SessionService(properties, authDatabase());
    }

    private JdbcTemplate authDatabase() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE runtime_state(id INTEGER PRIMARY KEY)");
        jdbc.update("INSERT INTO runtime_state(id) VALUES (1)");
        jdbc.execute("CREATE TABLE owner_account(id SMALLINT PRIMARY KEY CHECK(id=1),username VARCHAR(64) UNIQUE NOT NULL,password_salt VARCHAR(64) NOT NULL,password_hash VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE auth_sessions(id_hash VARCHAR(64) PRIMARY KEY,expires_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        return jdbc;
    }
}
