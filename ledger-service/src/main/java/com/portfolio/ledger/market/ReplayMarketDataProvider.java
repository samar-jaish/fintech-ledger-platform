package com.portfolio.ledger.market;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Serves prices previously recorded in our own price_history table. Real numbers, no network,
 * repeatable: a good mode for demos. The quote is the most recent recorded close and says so in its asOf.
 */
public class ReplayMarketDataProvider implements MarketDataProvider {

    private final PriceHistoryRepository history;

    public ReplayMarketDataProvider(PriceHistoryRepository history) {
        this.history = history;
    }

    @Override
    public String name() {
        return "replay";
    }

    @Override
    public Quote getQuote(String symbol) {
        String s = Symbols.normalize(symbol);
        PricePoint latest = history.findLatest(s)
                .orElseThrow(() -> new MarketDataException("No recorded prices for " + s));
        return new Quote(s, latest.close(), Quote.DEFAULT_CURRENCY,
                latest.date().atStartOfDay(ZoneOffset.UTC).toInstant(), name());
    }

    @Override
    public List<PricePoint> getDailyHistory(String symbol, LocalDate from, LocalDate to) {
        String s = Symbols.normalize(symbol);
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        return history.findRange(s, from, to);
    }
}
