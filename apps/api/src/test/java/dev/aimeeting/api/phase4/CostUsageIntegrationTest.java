package dev.aimeeting.api.phase4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aimeeting.api.auth.SessionService;
import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.meeting.MeetingDeletionService;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.usage.MonthlyUsage;
import dev.aimeeting.api.usage.UsageService;
import dev.aimeeting.api.usage.ledger.CostLedgerService;
import dev.aimeeting.api.usage.ledger.ReservationRequest;
import dev.aimeeting.api.usage.ledger.Settlement;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:cost_usage;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class CostUsageIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MeetingRepository meetings;
    @Autowired MeetingDeletionService deletions;
    @Autowired CostLedgerService ledger;
    @Autowired UsageService usage;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void resetOwnedTestDatabase() {
        jdbc.update("DELETE FROM ai_usage_records");
        jdbc.update("UPDATE app_settings SET monthly_budget_usd=10,warn_at_percent=80,per_meeting_max_minutes=240 WHERE id=1");
    }

    @Test
    void reservationsTriggerWarningsAndKeepUnknownBillsExplicitlyNullInJson() throws Exception {
        Meeting meeting = meetings.create("Synthetic pending cost");
        reserve(meeting.id(), "8.00");
        MonthlyUsage result = usage.get(currentMonth());
        assertThat(result.budgetState()).isEqualTo("WARNING");
        assertThat(result.reservedUsd()).isEqualByComparingTo("8");
        assertThat(result.budgetRemainingUsd()).isEqualByComparingTo("2");
        assertThat(result.totalActualUsd()).isNull();
        assertThat(result.unreconciledCount()).isEqualTo(1);

        String header = mvc.perform(post("/api/v1/session").header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"test-owner-token-00000000000000000\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("Set-Cookie");
        Cookie session = new Cookie(SessionService.COOKIE_NAME, header.substring(header.indexOf('=') + 1, header.indexOf(';')));
        JsonNode json = mapper.readTree(mvc.perform(get("/api/v1/usage").param("month", currentMonth().toString()).cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(json.has("totalActualUsd")).isTrue();
        assertThat(json.get("totalActualUsd").isNull()).isTrue();
        boolean pendingMeeting = false;
        for (JsonNode item : json.get("meetings")) {
            if (item.get("meetingId").asText().equals(meeting.id().toString())) {
                assertThat(item.has("actualUsd")).isTrue();
                assertThat(item.get("actualUsd").isNull()).isTrue();
                pendingMeeting = true;
            }
        }
        assertThat(pendingMeeting).isTrue();
        reserve(meeting.id(), "2.00");
        assertThat(usage.get(currentMonth()).budgetState()).isEqualTo("EXHAUSTED");
        jdbc.update("UPDATE app_settings SET monthly_budget_usd=0 WHERE id=1");
        assertThat(usage.get(currentMonth()).budgetState()).isEqualTo("DISABLED");
    }

    @Test
    void costMonthDoesNotRecountOldRecordingAndDeletedMeetingStillConsumesBudget() {
        Meeting meeting = meetings.create("Synthetic older recording");
        OffsetDateTime previous = currentMonth().minusMonths(1).atDay(12).atStartOfDay().atOffset(ZoneOffset.UTC);
        jdbc.update("UPDATE meetings SET created_at=? WHERE id=?", previous, meeting.id());
        jdbc.update("""
                INSERT INTO recordings(id,meeting_id,state,path,sha256,bytes,duration_ms,mime_type,chunk_count,completed_at)
                VALUES (?,?,'COMPLETED','recording.bin',?,123,61000,'audio/webm',1,?)
                """, UUID.randomUUID(), meeting.id(), "0".repeat(64), previous);
        UUID request = reserve(meeting.id(), "8");
        ledger.startDispatch(request);
        ledger.settle(request, new Settlement(new BigDecimal("3.25"), 61L, 12L, 4L));
        ledger.reconcile(request, new BigDecimal("3.50"), "synthetic invoice line");
        MonthlyUsage current = usage.get(currentMonth());
        assertThat(current.recordedSeconds()).isZero();
        assertThat(current.meetings()).filteredOn(item -> item.meetingId().equals(meeting.id())).singleElement()
                .satisfies(item -> {
                    assertThat(item.recordedSeconds()).isZero();
                    assertThat(item.recordingBytes()).isZero();
                    assertThat(item.estimatedUsd()).isEqualByComparingTo("3.25");
                    assertThat(item.actualUsd()).isEqualByComparingTo("3.50");
                });
        assertThat(usage.get(currentMonth().minusMonths(1)).recordedSeconds()).isEqualTo(61);
        deletions.delete(meeting.id(), meeting.revision());
        MonthlyUsage after = usage.get(currentMonth());
        assertThat(after.meetings()).noneMatch(item -> item.meetingId().equals(meeting.id()));
        assertThat(after.totalEstimatedUsd()).isEqualByComparingTo("3.25");
        assertThat(after.totalActualUsd()).isEqualByComparingTo("3.50");
        assertThat(after.budgetCommittedUsd()).isEqualByComparingTo("3.50");
        assertThat(after.budgetRemainingUsd()).isEqualByComparingTo("6.50");
    }

    private UUID reserve(UUID meetingId, String amount) {
        UUID request = UUID.randomUUID();
        ledger.reserve(request, new ReservationRequest(meetingId, "synthetic", "fixture", "SUMMARY", "fixture-v1",
                new BigDecimal(amount), 120));
        return request;
    }

    private YearMonth currentMonth() {
        return YearMonth.now(ZoneOffset.UTC);
    }
}
