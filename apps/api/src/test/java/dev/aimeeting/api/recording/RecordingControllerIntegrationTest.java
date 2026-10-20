package dev.aimeeting.api.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aimeeting.api.auth.SessionService;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class RecordingControllerIntegrationTest {
    private static final String ORIGIN = "http://localhost:5173";
    private static final String TOKEN = "test-owner-token-00000000000000000";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionService sessions;
    private Cookie session;

    @BeforeEach
    void resetDatabaseAndLogin() throws Exception {
        jdbc.update("UPDATE runtime_state SET active_meeting_id=NULL WHERE id=1");
        jdbc.update("UPDATE meetings SET status='STOPPED' WHERE status='STARTED'");
        session = new Cookie(SessionService.COOKIE_NAME, sessions.login(TOKEN));
    }

    @Test
    void hashMismatchCreatesNeitherChunkNorRecording() throws Exception {
        UUID meetingId = createAndStartLocalMeeting();
        mvc.perform(chunk(meetingId, 0, 0, 5, "0".repeat(64), "audio/webm", bytes("hello")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHUNK_HASH_MISMATCH"));

        mvc.perform(get("/api/v1/meetings/{id}/recording", meetingId).cookie(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recording_chunks rc JOIN recordings r ON r.id=rc.recording_id WHERE r.meeting_id=?",
                Integer.class, meetingId)).isZero();
    }

    @Test
    void uploadsOutOfOrderRetriesIdempotentlyAndCompletesInSequenceOrder() throws Exception {
        UUID meetingId = createAndStartLocalMeeting();
        byte[] first = bytes("hello");
        byte[] second = bytes("world");

        mvc.perform(chunk(meetingId, 1, 5, 10, sha(second), "audio/webm", second))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sequence").value(1));
        mvc.perform(chunk(meetingId, 0, 0, 5, sha(first), "audio/webm", first))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sequence").value(0));
        mvc.perform(chunk(meetingId, 0, 0, 5, sha(first), "audio/webm", first))
                .andExpect(status().isOk());
        byte[] conflicting = bytes("HELLO");
        mvc.perform(chunk(meetingId, 0, 0, 5, sha(conflicting), "audio/webm", conflicting))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHUNK_CONFLICT"));

        String expectedHash = sha(bytes("helloworld"));
        mvc.perform(post("/api/v1/meetings/{id}/recording-complete", meetingId).cookie(session)
                        .header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chunkCount\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.bytes").value(10))
                .andExpect(jsonPath("$.durationMs").value(10))
                .andExpect(jsonPath("$.chunkCount").value(2))
                .andExpect(jsonPath("$.sha256").value(expectedHash))
                .andExpect(jsonPath("$.path").doesNotExist());

        MvcResult audio = mvc.perform(get("/api/v1/meetings/{id}/recording/audio", meetingId).cookie(session))
                .andExpect(status().isOk()).andReturn();
        assertThat(audio.getResponse().getContentAsByteArray()).isEqualTo(bytes("helloworld"));

        mvc.perform(post("/api/v1/meetings/{id}/recording-complete", meetingId).cookie(session)
                        .header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chunkCount\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sha256").value(expectedHash));
        mvc.perform(chunk(meetingId, 2, 10, 11, sha(bytes("x")), "audio/webm", bytes("x")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORDING_COMPLETED"));
        mvc.perform(chunk(meetingId, 0, 0, 5, sha(first), "audio/webm", first))
                .andExpect(status().isOk());
    }

    @Test
    void completionRejectsSequenceGaps() throws Exception {
        UUID meetingId = createAndStartLocalMeeting();
        byte[] content = bytes("second");
        mvc.perform(chunk(meetingId, 1, 10, 20, sha(content), "audio/webm", content)).andExpect(status().isOk());

        mvc.perform(post("/api/v1/meetings/{id}/recording-complete", meetingId).cookie(session)
                        .header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chunkCount\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORDING_CHUNKS_MISSING"));
    }

    @ParameterizedTest
    @CsvSource({
            "audio/webm, webm",
            "audio/webm;codecs=opus, webm",
            "audio/ogg;codecs=opus, ogg",
            "AUDIO/WEBM, webm",
            "application/octet-stream, bin",
            "audio/x-unknown, bin"
    })
    void downloadUsesMediaExtensionAndPreservesOriginalBytes(String mimeType, String extension) throws Exception {
        UUID meetingId = createAndStartLocalMeeting();
        byte[] original = bytes("original recording bytes");
        mvc.perform(chunk(meetingId, 0, 0, 1000, sha(original), mimeType, original))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/meetings/{id}/recording-complete", meetingId).cookie(session)
                        .header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chunkCount\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sha256").value(sha(original)));

        MvcResult download = mvc.perform(get("/api/v1/meetings/{id}/recording/audio", meetingId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"meeting-" + meetingId + "-recording." + extension + "\""))
                .andReturn();
        assertThat(MediaType.parseMediaType(download.getResponse().getContentType()))
                .isEqualTo(MediaType.parseMediaType(mimeType));
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(original);
    }

    @Test
    void chunkEndpointsRequireAuthenticationAndBoundMalformedRequests() throws Exception {
        UUID meetingId = createAndStartLocalMeeting();
        byte[] one = bytes("x");
        mvc.perform(post("/api/v1/meetings/{id}/recording-chunks", meetingId)
                        .header("Origin", ORIGIN).contentType(MediaType.APPLICATION_OCTET_STREAM).content(one))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/meetings/{id}/recording-chunks", meetingId).cookie(session)
                        .header("Origin", ORIGIN).contentType(MediaType.APPLICATION_OCTET_STREAM).content(one))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/meetings/{id}/recording-chunks", meetingId).cookie(session)
                        .header("Origin", ORIGIN).contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(new byte[RecordingService.MAX_CHUNK_BYTES + 1]))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
    }

    @Test
    void logoutStopsLocalMeetingAndLateRecoveryUploadStillWorks() throws Exception {
        UUID meetingId = createAndStartLocalMeeting();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/session").cookie(session).header("Origin", ORIGIN))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM meetings WHERE id=?", String.class, meetingId))
                .isEqualTo("STOPPED");
        assertThat(jdbc.queryForObject("SELECT active_meeting_id FROM runtime_state WHERE id=1", UUID.class)).isNull();

        MvcResult login = mvc.perform(post("/api/v1/session").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String setCookie = login.getResponse().getHeader("Set-Cookie");
        String prefix = SessionService.COOKIE_NAME + "=";
        session = new Cookie(SessionService.COOKIE_NAME,
                setCookie.substring(prefix.length(), setCookie.indexOf(';')));
        byte[] recovered = bytes("recovered");
        mvc.perform(chunk(meetingId, 0, 0, 10, sha(recovered), "audio/webm", recovered))
                .andExpect(status().isOk());
    }

    @Test
    void finalizationDetectsOnDiskChunkCorruptionAndLeavesRecordingOpen() throws Exception {
        UUID meetingId = createAndStartLocalMeeting();
        byte[] original = bytes("original");
        mvc.perform(chunk(meetingId, 0, 0, 10, sha(original), "audio/webm", original))
                .andExpect(status().isOk());
        String storedPath = jdbc.queryForObject("SELECT rc.path FROM recording_chunks rc JOIN recordings r ON r.id=rc.recording_id WHERE r.meeting_id=?",
                String.class, meetingId);
        Files.write(Path.of(storedPath), bytes("corrupt"));

        mvc.perform(post("/api/v1/meetings/{id}/recording-complete", meetingId).cookie(session)
                        .header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chunkCount\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHUNK_INTEGRITY_FAILED"));
        mvc.perform(get("/api/v1/meetings/{id}/recording", meetingId).cookie(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("UPLOADING"));
    }

    private UUID createAndStartLocalMeeting() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/meetings").cookie(session).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Local recording\",\"inputSource\":\"LOCAL_RECORDING\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.inputSource").value("LOCAL_RECORDING"))
                .andReturn();
        JsonNode json = mapper.readTree(created.getResponse().getContentAsByteArray());
        UUID id = UUID.fromString(json.get("id").asText());
        mvc.perform(post("/api/v1/meetings/{id}/start", id).cookie(session).header("Origin", ORIGIN))
                .andExpect(status().isOk());
        return id;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder chunk(
            UUID meetingId, int sequence, long startMs, long endMs, String sha256, String mimeType, byte[] content) {
        return post("/api/v1/meetings/{id}/recording-chunks", meetingId).cookie(session).header("Origin", ORIGIN)
                .queryParam("sequence", Integer.toString(sequence))
                .queryParam("startMs", Long.toString(startMs))
                .queryParam("endMs", Long.toString(endMs))
                .queryParam("sha256", sha256)
                .queryParam("mimeType", mimeType)
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(content);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
