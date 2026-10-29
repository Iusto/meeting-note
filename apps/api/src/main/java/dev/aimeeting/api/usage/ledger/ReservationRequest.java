package dev.aimeeting.api.usage.ledger;

import java.math.BigDecimal;
import java.util.UUID;

public record ReservationRequest(
        UUID meetingId,
        String provider,
        String model,
        String operation,
        String priceVersion,
        BigDecimal reservedUsd,
        long maximumAudioSeconds,
        UUID topicId
) {
    public ReservationRequest(UUID meetingId, String provider, String model, String operation,
                              String priceVersion, BigDecimal reservedUsd, long maximumAudioSeconds) {
        this(meetingId, provider, model, operation, priceVersion, reservedUsd, maximumAudioSeconds, null);
    }
}
