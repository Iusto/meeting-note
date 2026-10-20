package dev.aimeeting.api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.aimeeting.api.config.AppProperties;
import dev.aimeeting.api.meeting.MeetingRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FakeSpeechProviderFailureTest {
    @Test
    void databaseFailureStillEmitsSanitizedTerminalEvents() throws Exception {
        MeetingRepository meetings = mock(MeetingRepository.class);
        when(meetings.transcript(any())).thenThrow(new IllegalStateException("private database detail"));
        AppProperties properties = new AppProperties("a-valid-owner-token-000000000000", List.of(), Path.of("target"), false, 1);
        List<SpeechEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch stopped = new CountDownLatch(1);
        try (FakeSpeechProvider provider = new FakeSpeechProvider(meetings, properties)) {
            provider.stream(UUID.randomUUID(), UUID.randomUUID(), event -> {
                events.add(event);
                if ("stream.stopped".equals(event.type())) stopped.countDown();
            });
            assertThat(stopped.await(2, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(events).extracting(SpeechEvent::type)
                .containsSubsequence("session.ready", "stream.error", "stream.stopped");
        assertThat(events).noneMatch(event -> String.valueOf(event).contains("private database detail"));
    }
}
