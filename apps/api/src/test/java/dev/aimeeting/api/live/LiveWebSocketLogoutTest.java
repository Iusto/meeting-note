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
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class LiveWebSocketLogoutTest {
    @Test
    void logoutClosesSocketsCreatedByThatAuthenticationSession() throws Exception {
        AppProperties properties = new AppProperties("a-valid-owner-token-000000000000", List.of(), Path.of("target"), false, 100);
        SessionService auth = new SessionService(properties);
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
    void localRecordingMeetingRejectsProviderWebSocketWithoutStoppingMeeting() throws Exception {
        AppProperties properties = new AppProperties("a-valid-owner-token-000000000000", List.of(), Path.of("target"), false, 100);
        SessionService auth = new SessionService(properties);
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
}
