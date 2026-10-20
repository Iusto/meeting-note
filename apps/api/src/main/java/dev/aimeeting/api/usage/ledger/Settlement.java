package dev.aimeeting.api.usage.ledger;

import java.math.BigDecimal;

public record Settlement(
        BigDecimal estimatedUsd,
        Long measuredAudioSeconds,
        Long inputTokens,
        Long outputTokens
) {}
