package dev.aimeeting.api.provider.deepgram;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Objects;

/** Hard duration and reservation-cost limits for one Deepgram transport. */
public final class PocLimits {
    private static final Duration MAX_POC_DURATION = Duration.ofMinutes(15);
    private static final int ESTIMATE_SCALE = 6;

    private final Duration maxDuration;
    private final BigDecimal maxEstimatedUsd;
    private final BigDecimal estimatedUsdPerMinute;

    /** Standalone paid-POC limits remain intentionally capped at fifteen minutes. */
    public PocLimits(Duration maxDuration, BigDecimal maxEstimatedUsd, BigDecimal estimatedUsdPerMinute) {
        this(maxDuration, maxEstimatedUsd, estimatedUsdPerMinute, MAX_POC_DURATION);
    }

    /** Production limits are supplied by server configuration and the cumulative ledger guard. */
    public static PocLimits production(Duration maxDuration, BigDecimal maxEstimatedUsd,
                                       BigDecimal estimatedUsdPerMinute) {
        return new PocLimits(maxDuration, maxEstimatedUsd, estimatedUsdPerMinute, null);
    }

    private PocLimits(Duration maxDuration, BigDecimal maxEstimatedUsd, BigDecimal estimatedUsdPerMinute,
                      Duration durationCeiling) {
        this.maxDuration = Objects.requireNonNull(maxDuration, "maxDuration");
        this.maxEstimatedUsd = Objects.requireNonNull(maxEstimatedUsd, "maxEstimatedUsd");
        this.estimatedUsdPerMinute = Objects.requireNonNull(estimatedUsdPerMinute, "estimatedUsdPerMinute");
        if (maxDuration.isZero() || maxDuration.isNegative()) {
            throw new IllegalArgumentException("Maximum duration must be positive");
        }
        if (durationCeiling != null && maxDuration.compareTo(durationCeiling) > 0) {
            throw new IllegalArgumentException("Maximum duration exceeds the POC limit");
        }
        if (maxEstimatedUsd.signum() <= 0 || estimatedUsdPerMinute.signum() <= 0) {
            throw new IllegalArgumentException("Cost limits and rates must be positive");
        }
        if (estimate(maxDuration, estimatedUsdPerMinute).compareTo(maxEstimatedUsd) > 0) {
            throw new IllegalArgumentException("Projected session cost exceeds the POC budget");
        }
    }

    public Duration maxDuration() { return maxDuration; }
    public BigDecimal maxEstimatedUsd() { return maxEstimatedUsd; }
    public BigDecimal estimatedUsdPerMinute() { return estimatedUsdPerMinute; }

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

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PocLimits that)) return false;
        return maxDuration.equals(that.maxDuration) && maxEstimatedUsd.equals(that.maxEstimatedUsd)
                && estimatedUsdPerMinute.equals(that.estimatedUsdPerMinute);
    }

    @Override public int hashCode() { return Objects.hash(maxDuration, maxEstimatedUsd, estimatedUsdPerMinute); }

    @Override public String toString() {
        return "PocLimits[maxDuration=" + maxDuration + ", maxEstimatedUsd=" + maxEstimatedUsd
                + ", estimatedUsdPerMinute=" + estimatedUsdPerMinute + "]";
    }
}
