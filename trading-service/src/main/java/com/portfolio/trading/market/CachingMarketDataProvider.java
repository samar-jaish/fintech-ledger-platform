package com.portfolio.trading.market;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Cache-aside in front of another MarketDataProvider: look in the cache first, on a miss ask the provider and store
 * the answer for a few seconds. Only single quotes are cached; history is read rarely and goes straight through.
 *
 * Why: real vendors rate-limit (the free tiers allow a few calls a minute) and many users ask for the same
 * symbols. A short TTL keeps prices fresh enough for a simulated exchange, and each trade still records the
 * {@code asOf} time of the price it used.
 */
public class CachingMarketDataProvider implements MarketDataProvider {

    private final MarketDataProvider delegate;
    private final QuoteCache cache;
    private final Duration ttl;

    public CachingMarketDataProvider(MarketDataProvider delegate, QuoteCache cache, Duration ttl) {
        this.delegate = delegate;
        this.cache = cache;
        this.ttl = ttl;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public Quote getQuote(String symbol) {
        String normalized = Symbols.normalize(symbol);
        Optional<Quote> hit = cache.get(delegate.name(), normalized);
        if (hit.isPresent()) {
            return hit.get();
        }
        Quote fresh = delegate.getQuote(normalized);
        cache.put(delegate.name(), fresh, ttl);
        return fresh;
    }

    @Override
    public List<PricePoint> getDailyHistory(String symbol, LocalDate from, LocalDate to) {
        return delegate.getDailyHistory(symbol, from, to);
    }
}
