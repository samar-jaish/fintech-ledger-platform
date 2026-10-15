package com.portfolio.ledger.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntry(long id, UUID transactionId, UUID accountId, Direction direction,
                          BigDecimal amount, Instant createdAt) { }
