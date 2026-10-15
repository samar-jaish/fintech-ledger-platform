package com.portfolio.trading;

import com.portfolio.trading.market.Symbols;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** A request to buy or sell at the current market price. Quantities may be fractional (up to 6 places). */
public record TradeRequest(String idempotencyKey, UUID accountId, String symbol, Side side, BigDecimal quantity) {

    private static final int MAX_KEY_LENGTH = 90;

    public TradeRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("idempotencyKey must be at most " + MAX_KEY_LENGTH + " characters");
        }
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(side, "side");
        symbol = Symbols.normalize(symbol);
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (quantity.stripTrailingZeros().scale() > 6) {
            throw new IllegalArgumentException("quantity supports at most 6 decimal places");
        }
    }

    /** Fingerprint of what the request asks for. Excludes the price, which is decided at execution time. */
    public String fingerprint() {
        return Hashing.sha256Hex(accountId + "|" + symbol + "|" + side + "|" + quantity.stripTrailingZeros().toPlainString());
    }
}
