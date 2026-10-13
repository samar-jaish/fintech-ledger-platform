package com.portfolio.ledger.market;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimulatedMarketDataProviderTest {

    // Monday 2026-06-15 14:30 UTC
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T14:30:00Z"), ZoneOffset.UTC);

    private final SimulatedMarketDataProvider provider = new SimulatedMarketDataProvider(42, CLOCK);

    @Test
    void quote_isPositive_normalized_andLabelledSimulated() {
        Quote q = provider.getQuote("aapl");
        assertEquals("AAPL", q.symbol());
        assertTrue(q.price().signum() > 0);
        assertEquals("simulated", q.source());
    }

    @Test
    void sameSeed_givesSameQuote_differentSeed_doesNot() {
        Quote a = provider.getQuote("MSFT");
        Quote b = new SimulatedMarketDataProvider(42, CLOCK).getQuote("MSFT");
        Quote c = new SimulatedMarketDataProvider(7, CLOCK).getQuote("MSFT");
        assertEquals(a.price(), b.price());
        assertNotEquals(a.price(), c.price());
    }

    @Test
    void history_isAscending_inRange_weekdaysOnly_andPositive() {
        LocalDate from = LocalDate.of(2026, 5, 1);
        LocalDate to = LocalDate.of(2026, 6, 10);
        List<PricePoint> points = provider.getDailyHistory("AAPL", from, to);
        assertFalse(points.isEmpty());
        for (int i = 0; i < points.size(); i++) {
            PricePoint p = points.get(i);
            assertFalse(p.date().isBefore(from));
            assertFalse(p.date().isAfter(to));
            assertTrue(p.close().signum() > 0);
            assertNotEquals(6, p.date().getDayOfWeek().getValue());
            assertNotEquals(7, p.date().getDayOfWeek().getValue());
            if (i > 0) {
                assertTrue(p.date().isAfter(points.get(i - 1).date()));
            }
        }
    }

    @Test
    void history_isStableAcrossCalls() {
        LocalDate from = LocalDate.of(2026, 5, 1);
        LocalDate to = LocalDate.of(2026, 5, 20);
        assertEquals(provider.getDailyHistory("NVDA", from, to), provider.getDailyHistory("NVDA", from, to));
    }

    @Test
    void malformedSymbols_andBadRange_areRejected() {
        assertThrows(IllegalArgumentException.class, () -> provider.getQuote("not a symbol"));
        assertThrows(IllegalArgumentException.class, () -> provider.getQuote(""));
        assertThrows(IllegalArgumentException.class,
                () -> provider.getDailyHistory("AAPL", LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 1)));
    }

    @Test
    void failureRateOne_alwaysThrowsMarketDataException() {
        var failing = new SimulatedMarketDataProvider(42, CLOCK, 0, 1.0);
        assertThrows(MarketDataException.class, () -> failing.getQuote("AAPL"));
    }

    @Test
    void constructor_validatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> new SimulatedMarketDataProvider(1, CLOCK, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SimulatedMarketDataProvider(1, CLOCK, 0, 1.5));
    }
}
