package com.portfolio.ledger.repository;

import com.portfolio.ledger.domain.*;

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

    /** Reads accounts with their version, without locking (optimistic strategy). */
    List<VersionedAccount> findVersioned(Collection<UUID> ids);

    /** Returns empty if the idempotency key already exists (i.e. a replay). */
    Optional<UUID> insertTransaction(String idempotencyKey, String description, String requestHash);

    StoredTransaction findTransactionByKey(String idempotencyKey);

    void insertEntry(UUID transactionId, UUID accountId, Direction direction, BigDecimal amount);

    /** Unconditional update (caller must hold the row lock). Also bumps the version. */
    void updateBalance(UUID accountId, BigDecimal newBalance);

    /** Optimistic update: succeeds only if the version is unchanged. Returns false on conflict. */
    boolean updateBalanceIfVersion(UUID accountId, BigDecimal newBalance, long expectedVersion);

    /** Newest entries first. */
    List<LedgerEntry> findEntries(UUID accountId, int limit);

    /** Accounts whose cached balance differs from credits minus debits. */
    List<BalanceMismatch> findBalanceMismatches();

    List<BalanceMismatch> findBalanceMismatches(Collection<UUID> accountIds);
}
