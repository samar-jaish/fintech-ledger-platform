package com.portfolio.ledger.market;

/** A market data provider could not produce the requested data (outage, rate limit, unknown symbol). */
public class MarketDataException extends RuntimeException {
    public MarketDataException(String message) {
        super(message);
    }

    public MarketDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
