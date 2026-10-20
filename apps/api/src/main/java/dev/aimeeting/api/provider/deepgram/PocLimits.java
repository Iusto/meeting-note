package dev.aimeeting.api.provider.deepgram;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Objects;

/** Hard duration and reservation-cost limits for a paid Deepgram POC session. */
public record PocLimits(Duration maxDuration, BigDecimal maxEstimatedUsd, BigDecimal estimatedUsdPerMinute) {
    private static final Duration MAX_POC_DURATION = Duration.ofMinutes(15);
    private static final int ESTIMATE_SCALE = 6;

    public PocLimits {
        Objects.requireNonNull(maxDuration, "maxDuration");
        Objects.requireNonNull(maxEstimatedUsd, "maxEstimatedUsd");
        Objects.requireNonNull(estimatedUsdPerMinute, "estimatedUsdPerMinute");
        if (maxDuration.isZero() || maxDuration.isNegative()) {
            throw new IllegalArgumentException("Maximum duration must be positive");
        }
        if (maxDuration.compareTo(MAX_POC_DURATION) > 0) {
            throw new IllegalArgumentException("Maximum duration exceeds the POC limit");
        }
        if (maxEstimatedUsd.signum() <= 0 || estimatedUsdPerMinute.signum() <= 0) {
            throw new IllegalArgumentException("Cost limits and rates must be positive");
        }
        if (estimate(maxDuration, estimatedUsdPerMinute).compareTo(maxEstimatedUsd) > 0) {
            throw new IllegalArgumentException("Projected session cost exceeds the POC budget");
        }
    }

    /**
     * Returns a conservative reservation estimate, not a supplier invoice calculation.
     */
    public BigDecimal maximumEstimatedUsd() {
        return estimate(maxDuration, estimatedUsdPerMinute);
    }

    private static BigDecimal estimate(Duration duration, BigDecimal ratePerMinute) {
        BigDecimal seconds = BigDecimal.valueOf(duration.getSeconds())
                .add(BigDecimal.valueOf(duration.getNano(), 9));
        return ratePerMinute.multiply(seconds)
                .divide(BigDecimal.valueOf(60), ESTIMATE_SCALE, RoundingMode.UP);
    }
}
