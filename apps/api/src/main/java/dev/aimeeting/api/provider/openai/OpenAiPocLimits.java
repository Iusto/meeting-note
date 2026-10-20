package dev.aimeeting.api.provider.openai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Objects;

/** Local request reservation; neither a tokenizer nor a supplier invoice guarantee. */
public record OpenAiPocLimits(Duration timeout, int maxInputTokens, int maxOutputTokens,
                              BigDecimal inputUsdPerMillion, BigDecimal outputUsdPerMillion,
                              BigDecimal maxEstimatedUsd) {
    public OpenAiPocLimits {
        Objects.requireNonNull(timeout);
        Objects.requireNonNull(inputUsdPerMillion);
        Objects.requireNonNull(outputUsdPerMillion);
        Objects.requireNonNull(maxEstimatedUsd);
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(2)) > 0
                || maxInputTokens < 1 || maxInputTokens > 200_000
                || maxOutputTokens < 1 || maxOutputTokens > 4096
                || inputUsdPerMillion.signum() <= 0 || outputUsdPerMillion.signum() <= 0
                || maxEstimatedUsd.signum() <= 0) {
            throw new IllegalArgumentException("Invalid OpenAI POC limits");
        }
        if (estimate(maxInputTokens, maxOutputTokens, inputUsdPerMillion, outputUsdPerMillion)
                .compareTo(maxEstimatedUsd) > 0) {
            throw new IllegalArgumentException("Projected request cost exceeds the POC budget");
        }
    }

    public BigDecimal maximumEstimatedUsd() {
        return estimate(maxInputTokens, maxOutputTokens, inputUsdPerMillion, outputUsdPerMillion);
    }

    public BigDecimal estimatedUsd(long inputTokens, long outputTokens) {
        if (inputTokens < 0 || outputTokens < 0) throw new IllegalArgumentException("Invalid token usage");
        return estimate(inputTokens, outputTokens, inputUsdPerMillion, outputUsdPerMillion);
    }

    private static BigDecimal estimate(long input, long output, BigDecimal inputRate, BigDecimal outputRate) {
        return inputRate.multiply(BigDecimal.valueOf(input)).add(outputRate.multiply(BigDecimal.valueOf(output)))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.UP);
    }
}
