package com.portfolio.ledger.repository;

import com.portfolio.ledger.domain.*;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerRepository {
    /** System and test accounts have no owning user. */
    default Account createAccount(String ownerName, String currency, boolean allowNegative) {
        return createAccount(ownerName, currency, allowNegative, null);
    }

    /** ownerSubject is the identity provider's user id (JWT "sub"); null for accounts that belong to no user. */
    Account createAccount(String ownerName, String currency, boolean allowNegative, String ownerSubject);

    boolean isOwnedBy(UUID accountId, String ownerSubject);

    Optional<Account> findAccount(UUID id);

    /** Accounts owned by one user (identity provider subject), oldest first. */
    List<Account> findAccountsByOwner(String ownerSubject);

    /** Locks rows (SELECT ... FOR UPDATE) in a stable id order to avoid deadlocks. */
    List<Account> lockAccounts(Collection<UUID> ids);

    /** Reads accounts with their version, without locking (optimistic strategy). */
    List<VersionedAccount> findVersioned(Collection<UUID> ids);

    /** Returns empty if the idempotency key already exists (i.e. a replay). */
    Optional<UUID> insertTransaction(String idempotencyKey, String description, String requestHash);

    StoredTransaction findTransactionByKey(String idempotencyKey);

    /**
     * Finds the oldest overdraft-allowed account with this name and currency, or creates it. Concurrent first calls
     * are serialized by a transaction-scoped advisory lock, so only one account is created. Call inside a transaction.
     */
    Account findOrCreateSystemAccount(String name, String currency);

    /** Adds an event to the outbox. Must run in the same transaction as the change it describes. */
    void insertOutboxEvent(String aggregateType, UUID aggregateId, String eventType, String payloadJson);

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
