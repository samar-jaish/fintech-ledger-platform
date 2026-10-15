package com.portfolio.trading.ledger;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What trading needs from the ledger service. It is an interface so the saga can be tested with a fake ledger that
 * fails on demand, and so Phase 3c can wrap the real client in retries and a circuit breaker without touching the saga.
 *
 * Failure model (the saga depends on it):
 *  - InsufficientFundsException, AccountNotFoundException, LedgerRejectedException: the ledger answered "no". Final.
 *  - LedgerUnavailableException: no usable answer (timeout, connection error, 5xx, auth problem). The transfer may or
 *    may not have happened, so the caller must retry with the same idempotency key.
 */
public interface LedgerClient {

    /** Reads an account as the caller (their token), so the ledger enforces ownership: someone else's account is "not found". */
    AccountView getAccount(UUID accountId, String bearerToken);

    /** The house account that is the counterparty of trades in this currency. */
    UUID exchangeAccountId(String currency);

    /** Moves money. Safe to repeat with the same idempotencyKey: the ledger applies it once. */
    void transfer(String idempotencyKey, UUID fromAccountId, UUID toAccountId, BigDecimal amount, String description);
}
