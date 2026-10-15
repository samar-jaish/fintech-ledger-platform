package com.portfolio.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A trade and its saga state. The price and where it came from are fixed when the trade is created.
 * rejectReason is set only when status is REJECTED (INSUFFICIENT_FUNDS, INSUFFICIENT_HOLDINGS, LEDGER_REJECTED).
 */
public record Trade(UUID id, String idempotencyKey, String requestHash, UUID accountId, UUID exchangeAccountId,
                    String symbol, Side side, BigDecimal quantity, BigDecimal price, BigDecimal notional,
                    String currency, String priceSource, Instant priceAsOf, Instant createdAt,
                    TradeStatus status, String rejectReason) { }
