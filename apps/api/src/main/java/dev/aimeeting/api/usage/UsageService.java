package dev.aimeeting.api.usage;

import dev.aimeeting.api.settings.AppSettings;
import dev.aimeeting.api.settings.SettingsRepository;
import dev.aimeeting.api.usage.MonthlyUsage.MeetingUsage;
import dev.aimeeting.api.usage.ledger.CostLedgerService;
import dev.aimeeting.api.usage.ledger.LedgerTotals;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class UsageService {
    private final JdbcTemplate jdbc;
    private final SettingsRepository settings;
    private final CostLedgerService ledger;

    public UsageService(JdbcTemplate jdbc, SettingsRepository settings, CostLedgerService ledger) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.ledger = ledger;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MonthlyUsage get(YearMonth month) {
        OffsetDateTime start = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        List<UsageRow> rows = jdbc.query("""
                SELECT m.id,m.title,m.input_source,m.status,r.id AS recording_id,
                       COALESCE(m.ended_at,m.started_at,m.created_at) AS reference_at,
                       COALESCE(m.started_at,m.created_at) AS recording_month_at,
                       CASE WHEN r.state='COMPLETED' THEN COALESCE(r.duration_ms,0) ELSE 0 END AS duration_ms,
                       CASE WHEN r.state='COMPLETED' THEN COALESCE(r.bytes,0) ELSE 0 END AS recording_bytes
                  FROM meetings m
                  LEFT JOIN recordings r ON r.meeting_id=m.id
                 WHERE (COALESCE(m.started_at,m.created_at) >= ?
                   AND COALESCE(m.started_at,m.created_at) < ?)
                    OR m.id IN (SELECT meeting_id FROM ai_usage_records WHERE reservation_month=? AND state<>'RELEASED')
                 ORDER BY COALESCE(m.started_at,m.created_at) DESC,m.created_at DESC
                """, (rs, row) -> new UsageRow(rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getString("input_source"), rs.getString("status"), rs.getObject("recording_id", UUID.class),
                rs.getObject("reference_at", OffsetDateTime.class),
                rs.getObject("recording_month_at", OffsetDateTime.class), rs.getLong("duration_ms") / 1000,
                rs.getLong("recording_bytes")), start, end, month.atDay(1));
        AppSettings current = settings.get();
        LedgerTotals totals = ledger.totals(month);
        Map<UUID, LedgerTotals> costsByMeeting = ledger.byMeeting(month);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<MeetingUsage> meetings = rows.stream().map(row -> {
            LedgerTotals costs = costsByMeeting.getOrDefault(row.meetingId(), LedgerTotals.zero());
            boolean recordingInMonth = !row.recordingMonthAt().isBefore(start) && row.recordingMonthAt().isBefore(end);
            return new MeetingUsage(row.meetingId(), row.title(), row.inputSource(),
                    recordingInMonth ? row.recordedSeconds() : 0, recordingInMonth ? row.recordingBytes() : 0,
                    costs.estimatedUsd(), costs.actualUsd(), costs.reservedUsd(), costs.budgetCommittedUsd(),
                    retentionReviewDue(row, current.recordingRetentionDays(), now));
        }).toList();
        long recordedSeconds = meetings.stream().mapToLong(MeetingUsage::recordedSeconds).sum();
        BigDecimal remaining = current.monthlyBudgetUsd().subtract(totals.budgetCommittedUsd()).max(BigDecimal.ZERO);
        String budgetState = current.monthlyBudgetUsd().signum() == 0 ? "DISABLED"
                : totals.budgetCommittedUsd().compareTo(current.monthlyBudgetUsd()) >= 0 ? "EXHAUSTED"
                : totals.budgetCommittedUsd().multiply(BigDecimal.valueOf(100))
                        .compareTo(current.monthlyBudgetUsd().multiply(BigDecimal.valueOf(current.warnAtPercent()))) >= 0
                        ? "WARNING" : "OK";
        return new MonthlyUsage(month.toString(), "USD", false, recordedSeconds, totals.estimatedUsd(), totals.actualUsd(),
                totals.reservedUsd(), totals.budgetCommittedUsd(), remaining, totals.unreconciledCount(),
                current.monthlyBudgetUsd(), current.warnAtPercent(), budgetState, meetings);
    }

    private boolean retentionReviewDue(UsageRow row, Integer retentionDays, OffsetDateTime now) {
        return retentionDays != null && row.recordingId() != null && !"STARTED".equals(row.status())
                && !row.referenceAt().plusDays(retentionDays).isAfter(now);
    }

    private record UsageRow(UUID meetingId, String title, String inputSource, String status, UUID recordingId,
                            OffsetDateTime referenceAt, OffsetDateTime recordingMonthAt,
                            long recordedSeconds, long recordingBytes) {}
}
