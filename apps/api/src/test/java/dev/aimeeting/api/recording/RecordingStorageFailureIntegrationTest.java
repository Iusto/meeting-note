package dev.aimeeting.api.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import dev.aimeeting.api.meeting.InputSource;
import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.storage.RecordingStorage;
import dev.aimeeting.api.web.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:recording_fault;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.recording-root=./target/test-recordings-fault"})
class RecordingStorageFailureIntegrationTest {
    @Autowired MeetingRepository meetings;
    @Autowired RecordingService recordings;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean RecordingStorage storage;

    @Test
    void failedChunkWriteRollsBackMetadataAndSameChunkCanBeRetried() throws Exception {
        UUID meeting = stoppedLocalMeeting();
        byte[] content = "synthetic chunk".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = hash(content);
        doThrow(new IOException("Synthetic storage failure")).when(storage).writeChunk(any(), anyInt(), any());
        assertThatThrownBy(() -> recordings.upload(meeting, 0, 0, 2000, hash, "audio/webm", content))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("RECORDING_STORAGE_FAILED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recordings WHERE meeting_id=?", Long.class, meeting)).isZero();
        assertThat(Files.exists(storage.resolve(meeting, "chunk-00000000.bin"))).isFalse();

        doCallRealMethod().when(storage).writeChunk(any(), anyInt(), any());
        recordings.upload(meeting, 0, 0, 2000, hash, "audio/webm", content);
        RecordingMetadata result = recordings.complete(meeting, 1);
        assertThat(result.state()).isEqualTo("COMPLETED");
        assertThat(result.sha256()).isEqualTo(hash);
        assertThat(Files.readAllBytes(recordings.audio(meeting).path())).isEqualTo(content);
    }

    @Test
    void failedAssemblyPreservesAcknowledgedChunksForCompletionRetry() throws Exception {
        UUID meeting = stoppedLocalMeeting();
        byte[] content = "synthetic recoverable audio".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = hash(content);
        recordings.upload(meeting, 0, 0, 2000, hash, "audio/webm", content);
        doThrow(new IOException("Synthetic finalization failure")).when(storage).assemble(any(), any());
        assertThatThrownBy(() -> recordings.complete(meeting, 1)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("RECORDING_STORAGE_FAILED"));
        assertThat(recordings.metadata(meeting).state()).isEqualTo("UPLOADING");
        assertThat(Files.readAllBytes(storage.resolve(meeting, "chunk-00000000.bin"))).isEqualTo(content);
        doCallRealMethod().when(storage).assemble(any(), any());
        assertThat(recordings.complete(meeting, 1).sha256()).isEqualTo(hash);
        assertThat(recordings.complete(meeting, 1).sha256()).isEqualTo(hash);
    }

    private UUID stoppedLocalMeeting() {
        Meeting meeting = meetings.create("Synthetic storage fault " + UUID.randomUUID(), InputSource.LOCAL_RECORDING);
        meetings.start(meeting.id());
        meetings.stop(meeting.id());
        return meeting.id();
    }

    private String hash(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
