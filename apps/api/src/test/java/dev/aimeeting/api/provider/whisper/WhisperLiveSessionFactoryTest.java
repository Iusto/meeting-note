package dev.aimeeting.api.provider.whisper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.aimeeting.api.config.AiConfiguration;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.meeting.MeetingRepository.FinalSegmentInput;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WhisperLiveSessionFactoryTest {
    @Test
    void savesContiguousWindowsAndTheFinalShortTailWithMeetingOffsets() throws Exception {
        var meetings = mock(MeetingRepository.class);
        var config = mock(AiConfiguration.class);
        var whisper = mock(WhisperClient.class);
        UUID meetingId = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        when(config.transcriptionMaximumSeconds()).thenReturn(600);
        when(meetings.createStreamSession(meetingId, "WHISPER", 1250)).thenReturn(streamId);
        when(whisper.transcribeLive(any())).thenAnswer(call -> {
            byte[] audio = call.getArgument(0);
            return List.of(new WhisperClient.ClipText(0L, audio.length / 32, "synthetic"));
        });
        var factory = new WhisperLiveSessionFactory(meetings, config, whisper);
        var stream = factory.open(meetingId, 1250, ignored -> {}).get(2, TimeUnit.SECONDS);
        try {
            for (int index = 0; index < 4; index++) stream.sendAudio(new byte[64000]).join();
            stream.sendAudio(new byte[3200]).join();
            stream.finish().get(5, TimeUnit.SECONDS);
            assertThat(stream.outcome().join().retry()).isFalse();
            verify(meetings).insertFinalBatchIfAbsent(meetingId, List.of(new FinalSegmentInput(
                    "whisper:" + streamId + ":1", 1, 1250, 9250, "synthetic", null, "UNKNOWN")));
            verify(meetings).insertFinalBatchIfAbsent(meetingId, List.of(new FinalSegmentInput(
                    "whisper:" + streamId + ":2", 2, 9250, 9350, "synthetic", null, "UNKNOWN")));
            verify(meetings).closeStreamSession(streamId, "USER_STOP");
        } finally {
            stream.close();
        }
    }
}
