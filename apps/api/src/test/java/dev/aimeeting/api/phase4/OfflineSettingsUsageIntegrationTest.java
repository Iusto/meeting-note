package dev.aimeeting.api.phase4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.aimeeting.api.auth.SessionService;
import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.meeting.MeetingRepository;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OfflineSettingsUsageIntegrationTest {
    private static final String ORIGIN = "http://localhost:5173";
    private static final String TOKEN = "test-owner-token-00000000000000000";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeetingRepository meetings;
    private Cookie session;

    @BeforeAll
    void login() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/session").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        String prefix = SessionService.COOKIE_NAME + "=";
        session = new Cookie(SessionService.COOKIE_NAME,
                setCookie.substring(prefix.length(), setCookie.indexOf(';')));
    }

    @BeforeEach
    void resetSettings() {
        jdbc.update("""
                UPDATE app_settings SET monthly_budget_usd=0.00,warn_at_percent=80,
                       per_meeting_max_minutes=240,recording_retention_days=NULL,revision=0 WHERE id=1
                """);
    }

    @Test
    void defaultsPersistAndRevisionProtectsFullUpdates() throws Exception {
        mvc.perform(get("/api/v1/settings").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyBudgetUsd").value(0.0))
                .andExpect(jsonPath("$.warnAtPercent").value(80))
                .andExpect(jsonPath("$.perMeetingMaxMinutes").value(240))
                .andExpect(jsonPath("$.recordingRetentionDays").isEmpty())
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.paidApiEnabled").value(false));

        String update = """
                {"monthlyBudgetUsd":25.50,"warnAtPercent":75,"perMeetingMaxMinutes":90,
                 "recordingRetentionDays":30,"revision":0,"paidApiEnabled":false}
                """;
        mvc.perform(patch("/api/v1/settings").cookie(session).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyBudgetUsd").value(25.5))
                .andExpect(jsonPath("$.recordingRetentionDays").value(30))
                .andExpect(jsonPath("$.revision").value(1));
        mvc.perform(get("/api/v1/settings").cookie(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.warnAtPercent").value(75));
        mvc.perform(patch("/api/v1/settings").cookie(session).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_REVISION"));
    }

    @Test
    void settingsValidationRejectsPaidModeInvalidBoundsAndMissingOrigin() throws Exception {
        String paid = """
                {"monthlyBudgetUsd":0,"warnAtPercent":80,"perMeetingMaxMinutes":240,
                 "recordingRetentionDays":null,"revision":0,"paidApiEnabled":true}
                """;
        mvc.perform(patch("/api/v1/settings").cookie(session).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content(paid))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAID_API_DISABLED"));
        mvc.perform(patch("/api/v1/settings").cookie(session).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyBudgetUsd\":1.001,\"warnAtPercent\":0,\"perMeetingMaxMinutes\":1441,\"recordingRetentionDays\":0,\"revision\":-1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(patch("/api/v1/settings").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content(paid))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_REJECTED"));
    }

    @Test
    void settingsAndUsageRequireAuthenticationAndMonthIsStrict() throws Exception {
        mvc.perform(get("/api/v1/settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/usage")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/usage").cookie(session).queryParam("month", "2026-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MONTH"));
        mvc.perform(get("/api/v1/usage").cookie(session).queryParam("month", "not-a-month"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MONTH"));
        mvc.perform(get("/api/v1/usage").cookie(session).queryParam("month", "+02026-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MONTH"));
        mvc.perform(get("/api/v1/usage").cookie(session).queryParam("month", "0000-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MONTH"));
        mvc.perform(get("/api/v1/usage").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(YearMonth.now(ZoneOffset.UTC).toString()));
    }

    @Test
    void usageCountsOnlyCompletedRecordingsWithinUtcMeetingMonthWithoutFabricatedCosts() throws Exception {
        String marker = UUID.randomUUID().toString();
        Meeting completed = meetingAt("Completed " + marker, OffsetDateTime.parse("2024-03-01T00:00:00Z"));
        Meeting unfinished = meetingAt("Unfinished " + marker, OffsetDateTime.parse("2024-03-31T23:59:59Z"));
        Meeting outside = meetingAt("Outside " + marker, OffsetDateTime.parse("2024-04-01T00:00:00Z"));
        insertRecording(completed.id(), "COMPLETED", 125_999L, 500L);
        insertRecording(unfinished.id(), "UPLOADING", 999_999L, 9_999L);
        insertRecording(outside.id(), "COMPLETED", 60_000L, 100L);

        mvc.perform(get("/api/v1/usage").cookie(session).queryParam("month", "2024-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2024-03"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.paidApiEnabled").value(false))
                .andExpect(jsonPath("$.recordedSeconds").value(125))
                .andExpect(jsonPath("$.totalEstimatedUsd").value(0.0))
                .andExpect(jsonPath("$.totalActualUsd").value(0.0))
                .andExpect(jsonPath("$.budgetUsd").value(0.0))
                .andExpect(jsonPath("$.budgetState").value("DISABLED"))
                .andExpect(jsonPath("$.meetings.length()").value(2))
                .andExpect(jsonPath("$.meetings[?(@.title == 'Completed " + marker + "')].recordedSeconds").value(125))
                .andExpect(jsonPath("$.meetings[?(@.title == 'Completed " + marker + "')].recordingBytes").value(500))
                .andExpect(jsonPath("$.meetings[?(@.title == 'Unfinished " + marker + "')].recordedSeconds").value(0))
                .andExpect(jsonPath("$.meetings[0].estimatedUsd").value(0.0))
                .andExpect(jsonPath("$.meetings[0].actualUsd").value(0.0))
                .andExpect(jsonPath("$.meetings[0].retentionReviewDue").value(false));

        jdbc.update("UPDATE app_settings SET recording_retention_days=1 WHERE id=1");
        mvc.perform(get("/api/v1/usage").cookie(session).queryParam("month", "2024-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meetings[?(@.title == 'Completed " + marker + "')].retentionReviewDue").value(true))
                .andExpect(jsonPath("$.meetings[?(@.title == 'Unfinished " + marker + "')].retentionReviewDue").value(true));

        mvc.perform(get("/api/v1/usage").cookie(session).queryParam("month", "1999-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordedSeconds").value(0))
                .andExpect(jsonPath("$.meetings.length()").value(0));
    }

    private Meeting meetingAt(String title, OffsetDateTime createdAt) {
        Meeting meeting = meetings.create(title);
        jdbc.update("UPDATE meetings SET started_at=NULL,created_at=? WHERE id=?", createdAt, meeting.id());
        return meeting;
    }

    private void insertRecording(UUID meetingId, String state, long durationMs, long bytes) {
        jdbc.update("""
                INSERT INTO recordings(id,meeting_id,state,path,sha256,bytes,duration_ms,mime_type,chunk_count,completed_at)
                VALUES (?,?,?,'recording.bin',?, ?,?,'audio/webm',1,?)
                """, UUID.randomUUID(), meetingId, state, "0".repeat(64), bytes, durationMs,
                "COMPLETED".equals(state) ? OffsetDateTime.now(ZoneOffset.UTC) : null);
    }
}
