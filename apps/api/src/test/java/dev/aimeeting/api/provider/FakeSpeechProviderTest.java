package dev.aimeeting.api.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.meeting.TranscriptSegment;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class FakeSpeechProviderTest {
    @Autowired MeetingRepository meetings;
    @Autowired SpeechProvider provider;

    @Test
    void emitsOrderedMockSequenceAndPersistsOnlyFinalWithCorrection() throws Exception {
        Meeting meeting = meetings.start(meetings.create("Mock stream").id());
        UUID sessionId = meetings.createStreamSession(meeting.id());
        List<SpeechEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch stopped = new CountDownLatch(1);
        provider.stream(meeting.id(), sessionId, event -> {
            events.add(event);
            if ("stream.stopped".equals(event.type())) stopped.countDown();
        });

        assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events).extracting(SpeechEvent::type).containsExactly(
                "session.ready", "transcript.partial", "transcript.final", "speaker.updated", "transcript.corrected", "stream.stopped");
        List<TranscriptSegment> transcript = meetings.transcript(meeting.id());
        assertThat(transcript).singleElement().satisfies(segment -> {
            assertThat(segment.originalText()).isEqualTo("이번 주 배포 일정을 확인하겠습니다.");
            assertThat(segment.displayText()).isEqualTo("이번 주 배포 일정을 확정하겠습니다.");
            assertThat(segment.speakerStatus()).isEqualTo("CONFIRMED");
            assertThat(segment.revision()).isEqualTo(1);
            assertThat(segment.transcriptStatus()).isEqualTo("CORRECTED");
        });
    }
}
