package com.portfolio.trading;

/**
 * replayed = true means this idempotency key was seen before. trade.status() tells whether the trade is
 * COMPLETED or still PENDING (the ledger could not be reached; it is retried automatically).
 */
public record TradeResult(Trade trade, boolean replayed) { }
