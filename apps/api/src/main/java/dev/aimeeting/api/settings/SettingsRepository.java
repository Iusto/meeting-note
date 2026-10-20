package dev.aimeeting.api.settings;

import dev.aimeeting.api.web.ApiException;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SettingsRepository {
    private final JdbcTemplate jdbc;

    public SettingsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public AppSettings get() {
        return jdbc.queryForObject("""
                SELECT monthly_budget_usd,warn_at_percent,per_meeting_max_minutes,
                       recording_retention_days,revision
                  FROM app_settings WHERE id=1
                """, (rs, row) -> new AppSettings(rs.getBigDecimal("monthly_budget_usd"),
                rs.getInt("warn_at_percent"), rs.getInt("per_meeting_max_minutes"),
                (Integer) rs.getObject("recording_retention_days"), rs.getInt("revision"), false));
    }

    @Transactional
    public AppSettings update(BigDecimal budget, int warnAtPercent, int maxMinutes,
                              Integer retentionDays, int expectedRevision) {
        Integer currentRevision = jdbc.queryForObject("SELECT revision FROM app_settings WHERE id=1 FOR UPDATE",
                Integer.class);
        if (currentRevision == null || currentRevision != expectedRevision) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_REVISION", "Settings revision is stale");
        }
        jdbc.update("""
                UPDATE app_settings
                   SET monthly_budget_usd=?,warn_at_percent=?,per_meeting_max_minutes=?,
                       recording_retention_days=?,revision=revision+1
                 WHERE id=1
                """, budget, warnAtPercent, maxMinutes, retentionDays);
        return get();
    }
}
