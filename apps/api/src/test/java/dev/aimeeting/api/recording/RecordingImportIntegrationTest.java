package dev.aimeeting.api.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aimeeting.api.auth.SessionService;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.provider.whisper.WhisperClient;
import dev.aimeeting.api.provider.openai.MeetingAiApplicationService;
import dev.aimeeting.api.summary.SummaryService;
import dev.aimeeting.api.web.ApiException;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:recording-import;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.paid-api-tests-enabled=false", "AI_SPEECH_PROVIDER=whisper", "AI_LLM_PROVIDER=codex",
        "CODEX_BRIDGE_TOKEN=synthetic-llm"
})
@AutoConfigureMockMvc
class RecordingImportIntegrationTest {
    private static final String ORIGIN = "http://localhost:5173";
    private static final String TOKEN = "test-owner-token-00000000000000000";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SessionService sessions;
    @Autowired RecordingImportService imports;
    @Autowired SummaryService summaries;
    @MockitoBean ImportedAudio audio;
    @MockitoBean WhisperClient whisper;
    @MockitoBean MeetingAiApplicationService ai;

    @Test
    void storesOriginalAndCanRetrySummaryWithoutTranscribingAgain() throws Exception {
        byte[] original = "synthetic m4a contents".getBytes(StandardCharsets.UTF_8);
        var segment = new MeetingRepository.FinalSegmentInput("upload:1", 1, 0, 1200,
                "회의 안건을 확인했습니다.", null, "UNKNOWN");
        when(audio.decode(any(), eq(ImportedAudio.Format.M4A), any(), anyInt())).thenReturn(1200L);
        when(whisper.transcribeFile(any(), eq(1200L))).thenReturn(List.of(segment));
        when(ai.generateSummary(any())).thenReturn(CompletableFuture.failedFuture(
                new ApiException(HttpStatus.BAD_GATEWAY, "SYNTHETIC_TEST", "Synthetic summary failure")));
        Cookie session = new Cookie(SessionService.COOKIE_NAME, sessions.login(TOKEN));
        var accepted = mvc.perform(post("/api/v1/meetings/import").cookie(session).header("Origin", ORIGIN)
                        .queryParam("title", "녹음 회의").queryParam("format", "m4a")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(original))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.inputSource").value("IMPORTED_RECORDING"))
                .andReturn();
        UUID id = UUID.fromString(mapper.readTree(accepted.getResponse().getContentAsByteArray()).path("id").asText());
        awaitState(id, "SUMMARY_FAILED");
        mvc.perform(get("/api/v1/meetings/{id}/transcript", id).cookie(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].displayText").value(segment.text()));
        mvc.perform(get("/api/v1/meetings/{id}/recording/audio", id).cookie(session))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"meeting-" + id + "-recording.m4a\""))
                .andExpect(content().bytes(original));
        when(ai.generateSummary(eq(id))).thenAnswer(call -> CompletableFuture.completedFuture(summaries.generate(id)));
        mvc.perform(post("/api/v1/meetings/{id}/import/retry", id).cookie(session).header("Origin", ORIGIN))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("SUMMARY_QUEUED"));
        awaitState(id, "COMPLETED");
        assertThat(summaries.latest(id).content().summary()).contains(segment.text());
        verify(whisper, times(1)).transcribeFile(any(), eq(1200L));
    }

    private void awaitState(UUID id, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline && !expected.equals(imports.status(id).state())) Thread.sleep(40);
        assertThat(imports.status(id).state()).isEqualTo(expected);
    }
}
