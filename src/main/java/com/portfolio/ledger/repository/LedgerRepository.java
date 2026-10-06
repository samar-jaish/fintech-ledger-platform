package com.portfolio.ledger.repository;

import com.portfolio.ledger.domain.Account;
import com.portfolio.ledger.domain.Direction;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerRepository {
    Account createAccount(String ownerName, String currency, boolean allowNegative);

    Optional<Account> findAccount(UUID id);

    /** Locks rows (SELECT ... FOR UPDATE) in a stable id order to avoid deadlocks. */
    List<Account> lockAccounts(Collection<UUID> ids);

    /** Returns empty if the idempotency key already exists (i.e. a replay). */
    Optional<UUID> insertTransaction(String idempotencyKey, String description);

    UUID findTransactionIdByKey(String idempotencyKey);

    void insertEntry(UUID transactionId, UUID accountId, Direction direction, BigDecimal amount);

    void updateBalance(UUID accountId, BigDecimal newBalance);
}
