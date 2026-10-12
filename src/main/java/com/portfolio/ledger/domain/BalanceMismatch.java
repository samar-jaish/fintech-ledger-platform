package com.portfolio.ledger.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** An account whose cached balance disagrees with the sum of its ledger entries. */
public record BalanceMismatch(UUID accountId, BigDecimal cachedBalance, BigDecimal computedBalance) {
    public BigDecimal difference() {
        return cachedBalance.subtract(computedBalance);
    }
}
