package dev.aimeeting.api.usage;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record MonthlyUsage(String month, String currency, boolean paidApiEnabled, long recordedSeconds,
                           BigDecimal totalEstimatedUsd,
                           @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal totalActualUsd, BigDecimal reservedUsd,
                           BigDecimal budgetCommittedUsd, BigDecimal budgetRemainingUsd, long unreconciledCount,
                           BigDecimal budgetUsd,
                           int warnAtPercent, String budgetState, List<MeetingUsage> meetings) {
    public record MeetingUsage(UUID meetingId, String title, String inputSource, long recordedSeconds,
                               long recordingBytes, BigDecimal estimatedUsd,
                               @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal actualUsd,
                               BigDecimal reservedUsd, BigDecimal budgetCommittedUsd,
                               boolean retentionReviewDue) {}
}
