package dev.aimeeting.api.live;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aimeeting.api.auth.SessionService;
import dev.aimeeting.api.config.AppProperties;
import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.provider.SpeechProvider;
import dev.aimeeting.api.provider.deepgram.DeepgramLiveSessionFactory;
import dev.aimeeting.api.provider.deepgram.DeepgramMeetingPoc;
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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class LiveWebSocketLogoutTest {
    @Test
    void liveSocketRelaysPcmAndGracefullyFinishesWithoutStoppingTheMeeting() throws Exception {
        AppProperties properties = new AppProperties("a-valid-owner-token-000000000000", List.of(), Path.of("target"), false, 100);
        SessionService auth = sessionService(properties);
        String authSessionId = auth.login("a-valid-owner-token-000000000000");
        UUID meetingId = UUID.randomUUID();
        MeetingRepository meetings = mock(MeetingRepository.class);
        SpeechProvider provider = mock(SpeechProvider.class);
        DeepgramLiveSessionFactory deepgram = mock(DeepgramLiveSessionFactory.class);
        DeepgramMeetingPoc live = mock(DeepgramMeetingPoc.class);
        when(meetings.find(meetingId)).thenReturn(new Meeting(meetingId, "live", "STARTED", "LIVE", null, null, null, 1));
        when(live.streamSessionId()).thenReturn(UUID.randomUUID());
        when(live.completion()).thenReturn(new CompletableFuture<>());
        when(live.sendAudio(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(live.finish()).thenReturn(new CompletableFuture<>());
        when(deepgram.open(eq(meetingId), eq(1_250L), any())).thenReturn(CompletableFuture.completedFuture(live));
        LiveWebSocketHandler handler = new LiveWebSocketHandler(meetings, provider, deepgram, new ObjectMapper(), auth);
        WebSocketSession socket = socket(authSessionId, meetingId, true);

        handler.afterConnectionEstablished(socket);
        handler.handleBinaryMessage(socket, new BinaryMessage(new byte[] {1, 0, 2, 0}));
        handler.afterConnectionClosed(socket, CloseStatus.NORMAL);

        verify(live).sendAudio(new byte[] {1, 0, 2, 0});
        verify(live).finish();
        verify(live, never()).close();
        verify(meetings, never()).closeStreamSession(any(), any());
        verify(meetings, never()).stop(meetingId);
        verifyNoInteractions(provider);
    }

    @Test
    void liveProviderStartupFailureEmitsTerminalErrorAndPreservesMeeting() throws Exception {
        AppProperties properties = new AppProperties("a-valid-owner-token-000000000000", List.of(), Path.of("target"), false, 100);
        SessionService auth = sessionService(properties);
        String authSessionId = auth.login("a-valid-owner-token-000000000000");
        UUID meetingId = UUID.randomUUID();
        MeetingRepository meetings = mock(MeetingRepository.class);
        SpeechProvider provider = mock(SpeechProvider.class);
        DeepgramLiveSessionFactory deepgram = mock(DeepgramLiveSessionFactory.class);
        when(meetings.find(meetingId)).thenReturn(new Meeting(meetingId, "live", "STARTED", "LIVE", null, null, null, 1));
        when(deepgram.open(eq(meetingId), eq(0L), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("synthetic provider failure")));
        LiveWebSocketHandler handler = new LiveWebSocketHandler(meetings, provider, deepgram, new ObjectMapper(), auth);
        WebSocketSession socket = socket(authSessionId, meetingId, false);

        handler.afterConnectionEstablished(socket);

        verify(socket).sendMessage(argThat(message -> message instanceof TextMessage text
                && text.getPayload().contains("\"type\":\"stream.error\"")));
        verify(socket).close(any(CloseStatus.class));
        verify(meetings, never()).stop(meetingId);
        verifyNoInteractions(provider);
    }

    @Test
    void liveAdmissionFailureEmitsTerminalErrorAndPreservesMeeting() throws Exception {
        AppProperties properties = new AppProperties("a-valid-owner-token-000000000000", List.of(), Path.of("target"), false, 100);
        SessionService auth = sessionService(properties);
        String authSessionId = auth.login("a-valid-owner-token-000000000000");
        UUID meetingId = UUID.randomUUID();
        MeetingRepository meetings = mock(MeetingRepository.class);
        SpeechProvider provider = mock(SpeechProvider.class);
        DeepgramLiveSessionFactory deepgram = mock(DeepgramLiveSessionFactory.class);
        when(meetings.find(meetingId)).thenReturn(new Meeting(meetingId, "live", "STARTED", "LIVE", null, null, null, 1));
        when(deepgram.open(eq(meetingId), eq(0L), any())).thenThrow(new IllegalStateException("MONTHLY_BUDGET_EXHAUSTED"));
        LiveWebSocketHandler handler = new LiveWebSocketHandler(meetings, provider, deepgram, new ObjectMapper(), auth);
        WebSocketSession socket = socket(authSessionId, meetingId, false);

        handler.afterConnectionEstablished(socket);

        verify(socket).sendMessage(argThat(message -> message instanceof TextMessage text
                && text.getPayload().contains("\"type\":\"stream.error\"")));
        verify(socket).sendMessage(argThat(message -> message instanceof TextMessage text
                && text.getPayload().contains("월 예산이 부족합니다")));
        verify(socket).close(any(CloseStatus.class));
        verify(meetings, never()).stop(meetingId);
        verifyNoInteractions(provider);
    }

    @Test
    void logoutImmediatelyCancelsLiveProviderAndStopsTheMeeting() throws Exception {
        AppProperties properties = new AppProperties("a-valid-owner-token-000000000000", List.of(), Path.of("target"), false, 100);
        SessionService auth = sessionService(properties);
        String authSessionId = auth.login("a-valid-owner-token-000000000000");
        UUID meetingId = UUID.randomUUID();
        MeetingRepository meetings = mock(MeetingRepository.class);
        SpeechProvider provider = mock(SpeechProvider.class);
        DeepgramLiveSessionFactory deepgram = mock(DeepgramLiveSessionFactory.class);
        DeepgramMeetingPoc live = mock(DeepgramMeetingPoc.class);
        when(meetings.find(meetingId)).thenReturn(new Meeting(meetingId, "live", "STARTED", "LIVE", null, null, null, 1));
        when(live.streamSessionId()).thenReturn(UUID.randomUUID());
        when(live.completion()).thenReturn(new CompletableFuture<>());
        when(deepgram.open(eq(meetingId), eq(0L), any())).thenReturn(CompletableFuture.completedFuture(live));
        LiveWebSocketHandler handler = new LiveWebSocketHandler(meetings, provider, deepgram, new ObjectMapper(), auth);
        WebSocketSession socket = socket(authSessionId, meetingId, false);
        handler.afterConnectionEstablished(socket);

        auth.logout(authSessionId);

        verify(live).close();
        verify(live, never()).finish();
        verify(meetings).stop(meetingId);
        verify(socket).close(any(CloseStatus.class));
    }

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
    void logoutDoesNotHoldTheLifecycleLockAcrossProviderStartup() throws Exception {
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
        CompletableFuture<Void> logout = CompletableFuture.runAsync(() -> auth.logout(authSessionId));
        logout.get(5, TimeUnit.SECONDS);
        if (auth.isValid(authSessionId)) throw new IllegalStateException("Logout was not committed during provider startup");
        verify(provider).stopMeeting(meetingId);
        verify(socket).close(any(CloseStatus.class));
        continueProvider.countDown();

        connection.get(5, TimeUnit.SECONDS);
        verify(provider).stream(eq(meetingId), eq(streamSessionId), any());
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

    private WebSocketSession socket(String authSessionId, UUID meetingId, boolean offset) {
        WebSocketSession socket = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put("authSessionId", authSessionId);
        when(socket.getAttributes()).thenReturn(attributes);
        when(socket.getUri()).thenReturn(URI.create("ws://localhost/api/v1/meetings/" + meetingId
                + "/live" + (offset ? "?audioOffsetMs=1250" : "")));
        when(socket.isOpen()).thenReturn(true);
        return socket;
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
