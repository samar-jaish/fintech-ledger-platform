package com.portfolio.ledger.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    /**
     * Stable SHA-256 fingerprint of what the request does (accounts and amount).
     * The description is cosmetic and excluded; 100 and 100.00 hash the same.
     */
    public String fingerprint() {
        String canonical = fromAccountId + "|" + toAccountId + "|" + amount.stripTrailingZeros().toPlainString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
