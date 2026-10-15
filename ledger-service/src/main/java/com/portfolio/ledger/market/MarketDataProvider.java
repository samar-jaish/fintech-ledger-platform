package com.portfolio.ledger.market;

import java.time.LocalDate;
import java.util.List;

/**
 * Source of market prices. Implementations: simulated (default, no network), replay (prices recorded in
 * our own database) and live (real vendors). Selected with the {@code market.provider} property.
 *
 * Contract (checked by MarketDataProviderContract): quotes have a positive price and the normalized symbol;
 * history is ascending by date, within the requested range, with positive closes; malformed symbols throw
 * IllegalArgumentException; data that cannot be produced throws MarketDataException.
 */
public interface MarketDataProvider {

    /** Short provider name: simulated, replay or live. */
    String name();

    Quote getQuote(String symbol);

    /** Daily closes between from and to (inclusive), oldest first. */
    List<PricePoint> getDailyHistory(String symbol, LocalDate from, LocalDate to);
}
