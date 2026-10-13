package com.portfolio.ledger.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/** One daily closing price. */
public record PricePoint(String symbol, LocalDate date, BigDecimal close, String source) {
    public PricePoint {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(source, "source");
        if (close.signum() <= 0) {
            throw new IllegalArgumentException("close must be positive");
        }
        close = close.setScale(4, RoundingMode.HALF_EVEN);
    }
}
