package dev.aimeeting.api.phase4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.aimeeting.api.auth.SessionService;
import dev.aimeeting.api.meeting.InputSource;
import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.meeting.MeetingDeletionService;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.meeting.RecordingDeletionReconciler;
import dev.aimeeting.api.recording.RecordingService;
import dev.aimeeting.api.storage.RecordingStorage;
import dev.aimeeting.api.web.ApiException;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MeetingDeletionIntegrationTest {
    private static final String ORIGIN = "http://localhost:5173";
    private static final String TOKEN = "test-owner-token-00000000000000000";

    @Autowired MockMvc mvc;
    @Autowired MeetingRepository meetings;
    @Autowired MeetingDeletionService deletions;
    @Autowired RecordingService recordings;
    @Autowired RecordingStorage storage;
    @Autowired RecordingDeletionReconciler reconciler;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionService sessions;
    private Cookie session;

    @BeforeAll
    void login() {
        session = new Cookie(SessionService.COOKIE_NAME, sessions.login(TOKEN));
    }

    @Test
    void endpointDeletesMeetingWithoutAudioAndEnforcesAuthOriginRevisionAndActiveState() throws Exception {
        Meeting unauthenticated = meetings.create("Unauthenticated delete");
        mvc.perform(delete("/api/v1/meetings/{id}", unauthenticated.id()).queryParam("revision", "0")
                        .header("Origin", ORIGIN))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/meetings/{id}", unauthenticated.id()).queryParam("revision", "0")
                        .cookie(session))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_REJECTED"));
        mvc.perform(delete("/api/v1/meetings/{id}", unauthenticated.id()).cookie(session).header("Origin", ORIGIN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        Meeting stale = meetings.create("Stale delete");
        mvc.perform(delete("/api/v1/meetings/{id}", stale.id()).queryParam("revision", "1")
                        .cookie(session).header("Origin", ORIGIN))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_REVISION"));

        Meeting activeCreated = meetings.create("Active delete");
        Meeting active = meetings.start(activeCreated.id());
        try {
            mvc.perform(delete("/api/v1/meetings/{id}", active.id())
                            .queryParam("revision", Integer.toString(active.revision()))
                            .cookie(session).header("Origin", ORIGIN))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEETING_ACTIVE"));
        } finally {
            meetings.stop(active.id());
        }

        Meeting noAudio = meetings.create("No audio delete");
        mvc.perform(delete("/api/v1/meetings/{id}", noAudio.id()).queryParam("revision", "0")
                        .cookie(session).header("Origin", ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.cleanupPending").value(false));
        assertThatThrownBy(() -> meetings.find(noAudio.id())).isInstanceOf(ApiException.class);
    }

    @Test
    void deletesPartialAndCompletedRecordingsAndCascadesMeetingRows() throws Exception {
        Meeting partial = stoppedLocal("Partial delete");
        upload(partial.id(), "partial");
        Path partialDirectory = storage.resolve(partial.id(), "chunk-00000000.bin").getParent();
        Meeting partialCurrent = meetings.find(partial.id());
        deletions.delete(partial.id(), partialCurrent.revision());
        assertThat(partialDirectory).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recordings WHERE meeting_id=?", Integer.class, partial.id())).isZero();

        Meeting completed = meetings.create("Completed delete", InputSource.LOCAL_RECORDING);
        meetings.start(completed.id());
        var segment = meetings.insertFinal(completed.id(), "delete-transcript", 1, 0, 10, "kept until delete", null);
        upload(completed.id(), "complete");
        recordings.complete(completed.id(), 1);
        completed = meetings.stop(completed.id());
        Path completedDirectory = storage.resolve(completed.id(), "recording.bin").getParent();
        deletions.delete(completed.id(), completed.revision());
        assertThat(completedDirectory).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transcript_segments WHERE id=?", Integer.class, segment.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recordings WHERE meeting_id=?", Integer.class, completed.id())).isZero();
    }

    @Test
    void unsafeRecordingEntryRefusesDeletionBeforeMutation() throws Exception {
        Meeting meeting = meetings.create("Unsafe storage delete");
        Path unexpected = storage.resolve(meeting.id(), "unexpected.txt");
        Files.writeString(unexpected, "retain");

        assertThatThrownBy(() -> deletions.delete(meeting.id(), meeting.revision()))
                .isInstanceOf(ApiException.class).hasMessageContaining("safely");
        assertThat(meetings.find(meeting.id()).id()).isEqualTo(meeting.id());
        assertThat(unexpected).exists();
        assertThat(unexpected.getParent().resolveSibling(".deleting-" + meeting.id())).doesNotExist();
    }

    @Test
    void startupReconciliationRestoresReferencedQuarantineAndPurgesOrphan() throws Exception {
        Meeting retained = meetings.create("Reconcile retained");
        Path retainedChunk = storage.writeChunk(retained.id(), 0, new byte[] {1});
        RecordingStorage.Quarantine retainedQuarantine = storage.quarantine(retained.id());
        assertThat(retainedChunk.getParent()).doesNotExist();
        reconciler.reconcile();
        assertThat(retainedChunk).exists();
        assertThat(retainedQuarantine.path()).doesNotExist();

        UUID orphanId = UUID.randomUUID();
        Path orphanChunk = storage.writeChunk(orphanId, 0, new byte[] {2});
        RecordingStorage.Quarantine orphanQuarantine = storage.quarantine(orphanId);
        reconciler.reconcile();
        assertThat(orphanChunk.getParent()).doesNotExist();
        assertThat(orphanQuarantine.path()).doesNotExist();

        Path root = storage.resolve(UUID.randomUUID(), "recording.bin").getParent().getParent();
        Path unexpectedQuarantine = Files.createDirectory(root.resolve(".deleting-not-a-uuid"));
        try {
            assertThatThrownBy(reconciler::reconcile).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("failed closed");
        } finally {
            Files.delete(unexpectedQuarantine);
        }
    }

    private Meeting stoppedLocal(String title) {
        Meeting meeting = meetings.create(title, InputSource.LOCAL_RECORDING);
        meetings.start(meeting.id());
        return meetings.stop(meeting.id());
    }

    private void upload(UUID meetingId, String value) throws Exception {
        byte[] content = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        recordings.upload(meetingId, 0, 0, content.length, hash, "audio/webm", content);
    }
}
