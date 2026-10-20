package dev.aimeeting.api.usage.ledger;

import java.math.BigDecimal;

public record LedgerTotals(
        BigDecimal estimatedUsd,
        BigDecimal actualUsd,
        BigDecimal reservedUsd,
        BigDecimal budgetCommittedUsd,
        long unreconciledCount,
        long requestCount
) {
    public static LedgerTotals zero() {
        BigDecimal zero = BigDecimal.ZERO.setScale(6);
        return new LedgerTotals(zero, zero, zero, zero, 0, 0);
    }
}
