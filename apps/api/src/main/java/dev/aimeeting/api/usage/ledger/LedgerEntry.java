package dev.aimeeting.api.usage.ledger;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.UUID;

public record LedgerEntry(
        UUID id,
        UUID meetingId,
        String provider,
        String model,
        String operation,
        String priceVersion,
        UsageState state,
        YearMonth reservationMonth,
        BigDecimal reservedUsd,
        BigDecimal estimatedUsd,
        BigDecimal actualUsd,
        String actualSource,
        long maximumAudioSeconds,
        Long measuredAudioSeconds,
        Long inputTokens,
        Long outputTokens,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        UUID topicId
) {}
