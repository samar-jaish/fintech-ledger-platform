package com.portfolio.ledger.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record TransferRequest(String idempotencyKey, UUID fromAccountId, UUID toAccountId,
                              BigDecimal amount, String description) {
    public TransferRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        Objects.requireNonNull(fromAccountId, "fromAccountId");
        Objects.requireNonNull(toAccountId, "toAccountId");
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (amount.stripTrailingZeros().scale() > 4) {
            throw new IllegalArgumentException("amount supports at most 4 decimal places");
        }
    }
}
