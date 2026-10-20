package dev.aimeeting.api.provider.deepgram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PocLimitsTest {
    @Test
    void calculatesConservativeSixDecimalReservationEstimate() {
        PocLimits limits = new PocLimits(Duration.ofSeconds(1), new BigDecimal("0.000084"),
                new BigDecimal("0.005"));

        assertThat(limits.maximumEstimatedUsd()).isEqualByComparingTo("0.000084");
    }

    @Test
    void includesFractionalSecondsAndRejectsProjectionAboveBudget() {
        PocLimits limits = new PocLimits(Duration.ofMillis(1500), new BigDecimal("0.001500"),
                new BigDecimal("0.06"));
        assertThat(limits.maximumEstimatedUsd()).isEqualByComparingTo("0.001500");

        assertThatThrownBy(() -> new PocLimits(Duration.ofMillis(1501), new BigDecimal("0.001500"),
                new BigDecimal("0.06")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Projected session cost exceeds the POC budget");
    }

    @Test
    void rejectsNullNonPositiveAndOverFifteenMinuteLimits() {
        assertThatThrownBy(() -> new PocLimits(null, BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PocLimits(Duration.ZERO, BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PocLimits(Duration.ofMinutes(15).plusNanos(1),
                BigDecimal.valueOf(100), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PocLimits(Duration.ofSeconds(1), BigDecimal.ZERO, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PocLimits(Duration.ofSeconds(1), BigDecimal.ONE, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
