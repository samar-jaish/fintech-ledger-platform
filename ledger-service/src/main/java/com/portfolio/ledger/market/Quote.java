package com.portfolio.ledger.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/** A price at a point in time. {@code source} says which provider produced it (simulated, replay, finnhub...). */
public record Quote(String symbol, BigDecimal price, String currency, Instant asOf, String source) {
    public static final String DEFAULT_CURRENCY = "USD";

    public Quote {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(source, "source");
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("price must be positive");
        }
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter code");
        }
        if (source.isBlank()) {
            throw new IllegalArgumentException("source is required");
        }
        price = price.setScale(4, RoundingMode.HALF_EVEN);
    }
}
