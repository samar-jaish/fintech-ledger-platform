package com.portfolio.ledger.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** An executed trade, including the price it executed at and where that price came from. */
public record Trade(UUID id, String idempotencyKey, String requestHash, UUID accountId, String symbol, Side side,
                    BigDecimal quantity, BigDecimal price, BigDecimal notional, String currency,
                    String priceSource, Instant priceAsOf, Instant executedAt) { }
