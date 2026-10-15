package com.portfolio.ledger.trading;

/** replayed = true means this idempotency key was already processed; nothing new was executed. */
public record TradeResult(Trade trade, boolean replayed) { }
