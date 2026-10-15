package com.portfolio.trading.market;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collection;

/**
 * Copies daily closes from the active provider into price_history. Each day is stored once, so running it
 * again only fills gaps and never re-asks a rate-limited vendor for data we already hold.
 */
@Service
public class PriceBackfillService {

    private final MarketDataProvider provider;
    private final PriceHistoryRepository history;

    public PriceBackfillService(MarketDataProvider provider, PriceHistoryRepository history) {
        this.provider = provider;
        this.history = history;
    }

    /** Returns the number of newly stored prices. */
    public int backfill(Collection<String> symbols, LocalDate from, LocalDate to) {
        int stored = 0;
        for (String symbol : symbols) {
            stored += history.saveAll(provider.getDailyHistory(symbol, from, to));
        }
        return stored;
    }
}
