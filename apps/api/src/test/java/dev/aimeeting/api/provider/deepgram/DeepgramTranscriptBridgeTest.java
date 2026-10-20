package dev.aimeeting.api.provider.deepgram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.meeting.TranscriptSegment;
import dev.aimeeting.api.provider.SpeechEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class DeepgramTranscriptBridgeTest {
    @Autowired MeetingRepository meetings;
    @Autowired JdbcTemplate jdbc;

    @Test
    void partialIsEmittedWithStableIdentityButOnlyFinalIsPersistedWithOffset() {
        Meeting meeting = meetings.create("bridge partial/final");
        UUID sessionId = meetings.createStreamSession(meeting.id(), "DEEPGRAM", 10_000);
        List<SpeechEvent> events = new ArrayList<>();
        DeepgramTranscriptBridge bridge = new DeepgramTranscriptBridge(
                meeting.id(), sessionId, 10_000, meetings, events::add);

        DeepgramMessage.Results partial = result(250, 800, "hello world", false,
                word("hello", 250, 400, 0), word("world", 450, 800, 0));
        bridge.accept(partial);
        bridge.accept(partial);
        assertThat(meetings.transcript(meeting.id())).isEmpty();
        assertThat(events).extracting(SpeechEvent::eventId).containsOnly(events.getFirst().eventId());

        bridge.accept(result(250, 800, "hello world", true,
                word("hello", 250, 400, 0), word("world", 450, 800, 0)));

        TranscriptSegment stored = meetings.transcript(meeting.id()).getFirst();
        assertThat(stored.startMs()).isEqualTo(10_250);
        assertThat(stored.endMs()).isEqualTo(10_800);
        assertThat(stored.originalText()).isEqualTo("hello world");
        assertThat(events.getLast().eventId()).isEqualTo(events.getFirst().eventId());
        assertThat(jdbc.queryForMap("SELECT provider,audio_offset_ms FROM stream_sessions WHERE id=?", sessionId))
                .containsEntry("provider", "DEEPGRAM").containsEntry("audio_offset_ms", 10_000L);
    }

    @Test
    void finalReplayIsIdempotentAndPreservesUserRevisionAndLock() {
        Meeting meeting = meetings.create("bridge replay");
        UUID sessionId = meetings.createStreamSession(meeting.id(), "DEEPGRAM", 0);
        List<SpeechEvent> firstEvents = new ArrayList<>();
        DeepgramMessage.Results finalResult = result(0, 500, "original", true,
                word("original", 0, 500, 0));
        new DeepgramTranscriptBridge(meeting.id(), sessionId, 0, meetings, firstEvents::add).accept(finalResult);
        TranscriptSegment stored = meetings.transcript(meeting.id()).getFirst();
        meetings.correct(meeting.id(), stored.id(), 0, "user edit", "USER", "manual", true);

        List<SpeechEvent> replayEvents = new ArrayList<>();
        DeepgramMessage.Results repartitionedReplay = result(0, 500, "original", true,
                word("ori", 0, 200, 0), word("ginal", 200, 500, 1));
        new DeepgramTranscriptBridge(meeting.id(), sessionId, 0, meetings, replayEvents::add)
                .accept(repartitionedReplay);

        TranscriptSegment replayed = meetings.transcript(meeting.id()).getFirst();
        assertThat(meetings.transcript(meeting.id())).hasSize(1);
        assertThat(replayed.originalText()).isEqualTo("original");
        assertThat(replayed.displayText()).isEqualTo("user edit");
        assertThat(replayed.revision()).isEqualTo(1);
        assertThat(replayed.userLocked()).isTrue();
        assertThat(replayEvents).isEmpty();
    }

    @Test
    void speakerChangesSplitLosslesslyAndSessionNamespacePreventsFalseIdentityReuse() {
        Meeting meeting = meetings.create("bridge speakers");
        UUID firstSession = meetings.createStreamSession(meeting.id(), "DEEPGRAM", 0);
        DeepgramTranscriptBridge first = new DeepgramTranscriptBridge(meeting.id(), firstSession, 0, meetings, event -> {});
        first.accept(result(0, 900, "Alice: hello, Bob: hi!", true,
                word("Alice", 0, 100, 0), word("hello", 150, 400, 0),
                word("Bob", 450, 550, 1), word("hi", 600, 850, 1)));

        List<TranscriptSegment> split = meetings.transcript(meeting.id());
        assertThat(split).hasSize(2);
        assertThat(split.stream().map(TranscriptSegment::originalText).reduce("", String::concat))
                .isEqualTo("Alice: hello, Bob: hi!");
        assertThat(split.get(0).speakerLabel()).isNotEqualTo(split.get(1).speakerLabel());

        UUID secondSession = meetings.createStreamSession(meeting.id(), "DEEPGRAM", 1_000);
        new DeepgramTranscriptBridge(meeting.id(), secondSession, 1_000, meetings, event -> {})
                .accept(result(0, 100, "again", true, word("again", 0, 100, 0)));
        assertThat(meetings.transcript(meeting.id()).getLast().speakerLabel())
                .isNotEqualTo(split.getFirst().speakerLabel());
    }

    @Test
    void missingWordsOrImpossibleAlignmentKeepsWholeOriginalAsUnknown() {
        Meeting meeting = meetings.create("bridge fallback");
        UUID sessionId = meetings.createStreamSession(meeting.id(), "DEEPGRAM", 0);
        DeepgramTranscriptBridge bridge = new DeepgramTranscriptBridge(meeting.id(), sessionId, 0, meetings, event -> {});
        bridge.accept(result(0, 100, "verbatim text", true));
        bridge.accept(result(200, 400, "cannot align exactly", true,
                word("different", 200, 300, 0), word("tokens", 300, 400, 1)));

        assertThat(meetings.transcript(meeting.id()))
                .extracting(TranscriptSegment::originalText).containsExactly("verbatim text", "cannot align exactly");
        assertThat(meetings.transcript(meeting.id()))
                .extracting(TranscriptSegment::speakerStatus).containsExactly("UNKNOWN", "OVERLAP");
    }

    @Test
    void hiddenLexicalTextAndInvalidWordTimingFallBackWithoutFalseSpeakerAssignment() {
        Meeting meeting = meetings.create("bridge safe alignment");
        UUID sessionId = meetings.createStreamSession(meeting.id(), "DEEPGRAM", 0);
        DeepgramTranscriptBridge bridge = new DeepgramTranscriptBridge(meeting.id(), sessionId, 0, meetings, event -> {});
        bridge.accept(result(0, 500, "one hidden two", true,
                word("one", 0, 100, 0), word("two", 200, 300, 1)));
        bridge.accept(result(600, 1_000, "first second", true,
                word("first", 700, 900, 0), word("second", 650, 950, 1)));

        assertThat(meetings.transcript(meeting.id())).hasSize(2);
        assertThat(meetings.transcript(meeting.id())).allSatisfy(segment -> {
            assertThat(segment.speakerLabel()).isNull();
            assertThat(segment.speakerStatus()).isEqualTo("OVERLAP");
        });
    }

    @Test
    void providerEventIdRemainsWithinDatabaseLimitForMaximumChannelIndexes() {
        Meeting meeting = meetings.create("bridge event id bound");
        UUID sessionId = meetings.createStreamSession(meeting.id(), "DEEPGRAM", 0);
        List<SpeechEvent> events = new ArrayList<>();
        DeepgramTranscriptBridge bridge = new DeepgramTranscriptBridge(meeting.id(), sessionId, 0, meetings, events::add);
        List<Integer> channels = java.util.stream.IntStream.range(0, 16).map(index -> Integer.MAX_VALUE - index)
                .boxed().toList();
        bridge.accept(new DeepgramMessage.Results(channels, Long.MAX_VALUE - 10, Long.MAX_VALUE - 1,
                "bounded", false, false, false, List.of()));

        assertThat(events.getFirst().eventId()).hasSizeLessThanOrEqualTo(200);
    }

    @Test
    void multiSpeakerFinalRollsBackEveryPartWhenSecondInsertFails() {
        Meeting meeting = meetings.create("bridge atomic final");
        UUID sessionId = meetings.createStreamSession(meeting.id(), "DEEPGRAM", 0);
        List<SpeechEvent> events = new ArrayList<>();
        DeepgramTranscriptBridge bridge = new DeepgramTranscriptBridge(meeting.id(), sessionId, 0, meetings, events::add);
        jdbc.execute("ALTER TABLE transcript_segments ADD CONSTRAINT reject_forced_second_part "
                + "CHECK (original_text <> 'force-db-failure')");
        try {
            assertThatThrownBy(() -> bridge.accept(result(0, 500, "ok force-db-failure", true,
                    word("ok", 0, 100, 0), word("force-db-failure", 200, 500, 1))))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM transcript_segments WHERE meeting_id=?", Integer.class, meeting.id()))
                    .isZero();
            assertThat(events).isEmpty();
        } finally {
            jdbc.execute("ALTER TABLE transcript_segments DROP CONSTRAINT reject_forced_second_part");
        }
    }

    @Test
    void partialArrivingAfterFinalDoesNotLeaveStaleUiEvent() {
        Meeting meeting = meetings.create("bridge stale partial");
        UUID sessionId = meetings.createStreamSession(meeting.id(), "DEEPGRAM", 0);
        List<SpeechEvent> events = new ArrayList<>();
        DeepgramTranscriptBridge bridge = new DeepgramTranscriptBridge(meeting.id(), sessionId, 0, meetings, events::add);
        bridge.accept(result(0, 100, "done", true, word("done", 0, 100, 0)));
        bridge.accept(result(0, 100, "stale", false, word("stale", 0, 100, 0)));

        assertThat(events).extracting(SpeechEvent::type).containsExactly("transcript.final");
        assertThat(meetings.transcript(meeting.id())).singleElement()
                .extracting(TranscriptSegment::originalText).isEqualTo("done");
    }

    @Test
    void startsAboveExistingDatabaseSequenceAndRejectsResultsAfterClose() {
        Meeting meeting = meetings.create("bridge lifecycle");
        meetings.insertFinal(meeting.id(), "existing", 50, 0, 1, "old", null);
        UUID sessionId = meetings.createStreamSession(meeting.id(), "DEEPGRAM", 0);
        DeepgramTranscriptBridge bridge = new DeepgramTranscriptBridge(meeting.id(), sessionId, 0, meetings, event -> {});
        bridge.accept(result(10, 20, "new", true, word("new", 10, 20, 0)));
        assertThat(meetings.transcript(meeting.id()).getLast().sequence()).isGreaterThan(50);

        bridge.close();
        assertThatThrownBy(() -> bridge.accept(result(30, 40, "late", true, word("late", 30, 40, 0))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        assertThat(meetings.transcript(meeting.id())).hasSize(2);
    }

    private DeepgramMessage.Results result(long startMs, long endMs, String transcript, boolean finalResult,
                                           DeepgramMessage.Word... words) {
        return new DeepgramMessage.Results(List.of(0), startMs, endMs, transcript, finalResult,
                finalResult, false, List.of(words));
    }

    private DeepgramMessage.Word word(String text, long startMs, long endMs, Integer speaker) {
        return new DeepgramMessage.Word(text, startMs, endMs, speaker);
    }
}
