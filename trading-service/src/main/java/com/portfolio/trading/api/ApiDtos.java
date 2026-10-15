package com.portfolio.trading.api;

import com.portfolio.trading.Side;
import com.portfolio.trading.Trade;
import com.portfolio.trading.TradeRequest;
import com.portfolio.trading.TradeStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Request and response bodies of the trading API. Requests validate themselves (IllegalArgumentException is HTTP 400). */
final class ApiDtos {

    private static final int MAX_KEY_LENGTH = 64;

    private ApiDtos() { }

    record TradeBody(UUID accountId, String symbol, Side side, BigDecimal quantity) {
        TradeRequest toRequest(String idempotencyKey) {
            return new TradeRequest(key(idempotencyKey), required(accountId, "accountId"), required(symbol, "symbol"),
                    required(side, "side"), required(quantity, "quantity"));
        }
    }

    /** status is COMPLETED, or PENDING when the ledger could not confirm yet (it is retried automatically). */
    record TradeResponse(UUID id, UUID accountId, String symbol, Side side, BigDecimal quantity, BigDecimal price,
                         BigDecimal notional, String currency, String priceSource, Instant priceAsOf,
                         Instant createdAt, TradeStatus status, String rejectReason, boolean replayed) {
        static TradeResponse from(Trade t, boolean replayed) {
            return new TradeResponse(t.id(), t.accountId(), t.symbol(), t.side(), t.quantity(), t.price(),
                    t.notional(), t.currency(), t.priceSource(), t.priceAsOf(), t.createdAt(), t.status(),
                    t.rejectReason(), replayed);
        }
    }

    static String key(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }
        return idempotencyKey;
    }

    static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
