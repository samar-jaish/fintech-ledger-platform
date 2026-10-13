package com.portfolio.ledger.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TradeRequestTest {

    private static final UUID ACCOUNT = UUID.randomUUID();

    private static TradeRequest req(String key, String symbol, Side side, String qty) {
        return new TradeRequest(key, ACCOUNT, symbol, side, new BigDecimal(qty));
    }

    @Test
    void symbol_isNormalized() {
        assertEquals("AAPL", req("k", " aapl ", Side.BUY, "1").symbol());
    }

    @Test
    void fingerprint_ignoresTrailingZeros_butNotSideOrQuantity() {
        assertEquals(req("a", "AAPL", Side.BUY, "1.50").fingerprint(), req("b", "AAPL", Side.BUY, "1.5").fingerprint());
        assertNotEquals(req("a", "AAPL", Side.BUY, "1").fingerprint(), req("a", "AAPL", Side.SELL, "1").fingerprint());
        assertNotEquals(req("a", "AAPL", Side.BUY, "1").fingerprint(), req("a", "AAPL", Side.BUY, "2").fingerprint());
    }

    @Test
    void invalidInputs_areRejected() {
        assertThrows(IllegalArgumentException.class, () -> req(" ", "AAPL", Side.BUY, "1"));
        assertThrows(IllegalArgumentException.class, () -> req("x".repeat(91), "AAPL", Side.BUY, "1"));
        assertThrows(IllegalArgumentException.class, () -> req("k", "AAPL", Side.BUY, "0"));
        assertThrows(IllegalArgumentException.class, () -> req("k", "AAPL", Side.BUY, "-1"));
        assertThrows(IllegalArgumentException.class, () -> req("k", "AAPL", Side.BUY, "0.0000001"));
        assertThrows(IllegalArgumentException.class, () -> req("k", "bad symbol", Side.BUY, "1"));
        assertDoesNotThrow(() -> req("k", "AAPL", Side.BUY, "0.123456"));
    }
}
