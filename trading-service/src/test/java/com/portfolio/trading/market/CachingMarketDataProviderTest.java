package com.portfolio.trading.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CachingMarketDataProviderTest {

    static class CountingProvider implements MarketDataProvider {
        int quoteCalls;
        int historyCalls;
        boolean fail;

        @Override
        public String name() {
            return "test";
        }

        @Override
        public Quote getQuote(String symbol) {
            quoteCalls++;
            if (fail) {
                throw new MarketDataException("vendor down");
            }
            return new Quote(symbol, new BigDecimal("100.00").add(BigDecimal.valueOf(quoteCalls)), "USD", Instant.now(), "test");
        }

        @Override
        public List<PricePoint> getDailyHistory(String symbol, LocalDate from, LocalDate to) {
            historyCalls++;
            return List.of();
        }
    }

    static class MapCache implements QuoteCache {
        final Map<String, Quote> store = new HashMap<>();

        @Override
        public Optional<Quote> get(String provider, String symbol) {
            return Optional.ofNullable(store.get(provider + ":" + symbol));
        }

        @Override
        public void put(String provider, Quote quote, Duration ttl) {
            store.put(provider + ":" + quote.symbol(), quote);
        }
    }

    private final CountingProvider provider = new CountingProvider();
    private final MapCache cache = new MapCache();
    private final CachingMarketDataProvider caching = new CachingMarketDataProvider(provider, cache, Duration.ofSeconds(5));

    @Test
    void secondRequestForTheSameSymbolComesFromTheCache() {
        Quote first = caching.getQuote("AAPL");
        Quote second = caching.getQuote("aapl");   // same symbol after normalizing

        assertThat(second).isEqualTo(first);
        assertThat(provider.quoteCalls).isEqualTo(1);
    }

    @Test
    void differentSymbolsAreCachedSeparately() {
        caching.getQuote("AAPL");
        caching.getQuote("MSFT");

        assertThat(provider.quoteCalls).isEqualTo(2);
    }

    @Test
    void providerErrorsAreNotCached() {
        provider.fail = true;
        assertThatThrownBy(() -> caching.getQuote("AAPL")).isInstanceOf(MarketDataException.class);
        assertThat(cache.store).isEmpty();

        provider.fail = false;
        assertThat(caching.getQuote("AAPL").symbol()).isEqualTo("AAPL");
    }

    @Test
    void invalidSymbolsAreRejectedBeforeAnythingIsLookedUp() {
        assertThatThrownBy(() -> caching.getQuote("not a symbol!")).isInstanceOf(IllegalArgumentException.class);
        assertThat(provider.quoteCalls).isZero();
    }

    @Test
    void historyGoesStraightToTheProvider() {
        caching.getDailyHistory("AAPL", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        caching.getDailyHistory("AAPL", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        assertThat(provider.historyCalls).isEqualTo(2);
    }
}
