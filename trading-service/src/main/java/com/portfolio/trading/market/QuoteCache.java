package com.portfolio.trading.market;

import java.time.Duration;
import java.util.Optional;

/** A short-lived store of recent quotes. Implementations must never throw: a cache problem is a cache miss. */
public interface QuoteCache {

    Optional<Quote> get(String provider, String symbol);

    void put(String provider, Quote quote, Duration ttl);
}
