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
    void speakerGroupsFlattenAndDetachWithoutTouchingProviderLabels() {
        var meeting = meetings.create("Merging");
        meetings.renameSpeaker(meeting.id(), "a", "Alice", "");
        meetings.renameSpeaker(meeting.id(), "b", "Bob", "");
        meetings.mergeSpeaker(meeting.id(), "c", "a", null);
        meetings.mergeSpeaker(meeting.id(), "a", "b", null);
        assertThat(meetings.speakerNames(meeting.id())).contains(
                new MeetingRepository.SpeakerName("a", "Alice", "b"),
                new MeetingRepository.SpeakerName("c", null, "b"));
        assertThatThrownBy(() -> meetings.mergeSpeaker(meeting.id(), "b", "c", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> meetings.mergeSpeaker(meeting.id(), "c", null, null)).isInstanceOf(ApiException.class);
        meetings.mergeSpeaker(meeting.id(), "c", null, "b");
        var segment = meetings.insertFinal(meeting.id(), "after-merge", 1, 0, 10, "Original", "a");
        assertThat(segment.speakerLabel()).isEqualTo("a");
        assertThat(meetings.speakerNames(meeting.id())).doesNotContain(new MeetingRepository.SpeakerName("c", null, "b"));
        assertThat(meetings.search("Bob")).extracting(Meeting::id).contains(meeting.id());
        assertThat(meetings.search("Alice")).extracting(Meeting::id).doesNotContain(meeting.id());
    }

    @Test
    void individualSpeakerCorrectionsAreReversibleIsolatedAndPreserveProviderUpdates() {
        var meeting = meetings.create("Speaker override");
        var other = meetings.create("Other");
        var segment = meetings.insertFinal(meeting.id(), "speech", 1, 0, 10, "Original", "a");
        meetings.renameSpeaker(meeting.id(), "b", "Bob", "");
        assertThatThrownBy(() -> meetings.overrideSpeaker(other.id(), segment.id(), "b", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> meetings.overrideSpeaker(meeting.id(), segment.id(), "missing", null)).isInstanceOf(ApiException.class);
        meetings.overrideSpeaker(meeting.id(), segment.id(), "b", null);
        meetings.updateSpeaker(segment.id(), "c", "TENTATIVE");
        assertThat(meetings.speakerOverrides(meeting.id())).containsExactly(new MeetingRepository.SpeakerOverride(segment.id(), "b"));
        assertThatThrownBy(() -> meetings.overrideSpeaker(meeting.id(), segment.id(), null, null)).isInstanceOf(ApiException.class);
        meetings.overrideSpeaker(meeting.id(), segment.id(), null, "b");
        assertThat(meetings.speakerOverrides(meeting.id())).isEmpty();
        assertThat(meetings.transcript(meeting.id()).getFirst().originalText()).isEqualTo("Original");
        assertThat(meetings.transcript(meeting.id()).getFirst().speakerLabel()).isEqualTo("c");
    }

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
    @Test
    void speakerNamesSurviveNewFinalsAndRemainIsolatedWithoutChangingOriginals() {
        Meeting meeting = meetings.create("Live speaker names");
        Meeting other = meetings.create("Other meeting");
        meetings.renameSpeaker(meeting.id(), "speaker_0", "Alice", "");
        TranscriptSegment segment = meetings.insertFinal(meeting.id(), "named-final", 1, 0, 10, "original", "speaker_0");
        assertThat(meetings.speakerNames(meeting.id())).containsExactly(new MeetingRepository.SpeakerName("speaker_0", "Alice"));
        assertThat(meetings.speakerNames(other.id())).isEmpty();
        assertThat(segment.originalText()).isEqualTo("original");
        assertThat(segment.speakerLabel()).isEqualTo("speaker_0");
        assertThatThrownBy(() -> meetings.renameSpeaker(meeting.id(), "speaker_0", "stale", ""))
                .isInstanceOf(ApiException.class);
        meetings.renameSpeaker(meeting.id(), "speaker_0", "", "Alice");
        assertThat(meetings.speakerNames(meeting.id())).isEmpty();
        assertThat(meetings.transcript(meeting.id()).getFirst().originalText()).isEqualTo("original");
    }
}
