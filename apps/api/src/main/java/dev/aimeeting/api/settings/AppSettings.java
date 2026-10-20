package dev.aimeeting.api.settings;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AppSettings(BigDecimal monthlyBudgetUsd, int warnAtPercent, int perMeetingMaxMinutes,
                          Integer recordingRetentionDays, int revision, boolean paidApiEnabled) {}
