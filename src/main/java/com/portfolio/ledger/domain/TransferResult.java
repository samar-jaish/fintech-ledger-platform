package com.portfolio.ledger.domain;

import java.util.UUID;

/** replayed = true means this idempotency key was already processed; nothing new was applied. */
public record TransferResult(UUID transactionId, boolean replayed) { }
