package com.portfolio.ledger.batch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One account's end-of-day figures. See V7__daily_balances.sql for what each field means. */
public record DailyBalance(UUID accountId, LocalDate businessDate, BigDecimal closingBalance,
                           BigDecimal cachedBalance, long entryCount, boolean reconciled) { }
