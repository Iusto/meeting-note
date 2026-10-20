package dev.aimeeting.api.meeting;

import static org.assertj.core.api.Assertions.*;

import dev.aimeeting.api.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class MeetingRepositoryTest {
    @Autowired MeetingRepository meetings;

    @Test
    void finalEventsAreIdempotentAndOriginalTextRemainsUnchangedAcrossCorrections() {
        Meeting meeting = meetings.create("Idempotency test");
        TranscriptSegment first = meetings.insertFinal(meeting.id(), "event-1", 1, 10, 20, "original", "speaker_0");
        TranscriptSegment duplicate = meetings.insertFinal(meeting.id(), "event-1", 1, 10, 20, "different", "speaker_0");
        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(duplicate.originalText()).isEqualTo("original");
        assertThat(meetings.transcript(meeting.id())).hasSize(1);

        TranscriptSegment corrected = meetings.correct(meeting.id(), first.id(), 0, "user edit", "USER", "typo", true);
        assertThat(corrected.originalText()).isEqualTo("original");
        assertThat(corrected.displayText()).isEqualTo("user edit");
        assertThat(corrected.userLocked()).isTrue();
        assertThatThrownBy(() -> meetings.correct(meeting.id(), first.id(), 0, "stale", "USER", null, true))
                .isInstanceOf(ApiException.class).hasMessageContaining("stale");
        assertThatThrownBy(() -> meetings.correct(meeting.id(), first.id(), 1, "mock overwrite", "MOCK", null, false))
                .isInstanceOf(ApiException.class).hasMessageContaining("locked");
    }

    @Test
    void finalWithoutSpeakerIsStoredAsUnknown() {
        Meeting meeting = meetings.create("Unknown speaker test");
        TranscriptSegment segment = meetings.insertFinal(meeting.id(), "no-speaker", 1, 0, 10, "voice", null);
        assertThat(segment.speakerLabel()).isNull();
        assertThat(segment.speakerStatus()).isEqualTo("UNKNOWN");
    }

    @Test
    void duplicateFinalReplayRemainsIdempotentAfterMeetingStops() {
        Meeting meeting = meetings.create("Stopped replay");
        TranscriptSegment stored = meetings.insertFinal(meeting.id(), "replayed-event", 1, 0, 10,
                "persisted", null);
        meetings.stop(meeting.id());

        TranscriptSegment replayed = meetings.insertFinal(meeting.id(), "replayed-event", 1, 0, 10,
                "different replay payload", null);

        assertThat(replayed.id()).isEqualTo(stored.id());
        assertThat(replayed.originalText()).isEqualTo("persisted");
        assertThat(meetings.transcript(meeting.id())).hasSize(1);
    }
}
