package com.portfolio.ledger.service;

import com.portfolio.ledger.domain.*;
import com.portfolio.ledger.outbox.TransferCompleted;
import com.portfolio.ledger.repository.LedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Ledger operations using pessimistic row locking (SELECT ... FOR UPDATE). */
@Service
public class LedgerService {

    private static final int MAX_STATEMENT_LIMIT = 1000;

    private final LedgerRepository repo;

    public LedgerService(LedgerRepository repo) {
        this.repo = repo;
    }

    public Account openAccount(String ownerName, String currency) {
        return repo.createAccount(ownerName, currency, false);
    }

    /** An account owned by the user identified by the identity provider's subject id. */
    public Account openAccountFor(String ownerSubject, String ownerName, String currency) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject is required");
        }
        return repo.createAccount(ownerName, currency, false, ownerSubject);
    }

    /** The accounts of one user, oldest first. Never includes house accounts, which have no owner. */
    @Transactional(readOnly = true)
    public List<Account> getAccountsOwnedBy(String ownerSubject) {
        return repo.findAccountsByOwner(ownerSubject);
    }

    @Transactional(readOnly = true)
    public boolean isOwnedBy(UUID accountId, String ownerSubject) {
        return repo.isOwnedBy(accountId, ownerSubject);
    }

    /** House account used as the counterparty for deposits/withdrawals. May go negative. */
    @Transactional
    public Account findOrCreateSystemAccount(String name, String currency) {
        return repo.findOrCreateSystemAccount(name, currency);
    }

    public Account openSystemAccount(String name, String currency) {
        return repo.createAccount(name, currency, true);
    }

    /**
     * Double-entry transfer: one DEBIT + one CREDIT of the same amount, atomically.
     * Idempotent per idempotencyKey. Uses pessimistic row locks on both accounts.
     */
    @Transactional
    public TransferResult transfer(TransferRequest req) {
        Optional<UUID> newTx = repo.insertTransaction(req.idempotencyKey(), req.description(), req.fingerprint());
        if (newTx.isEmpty()) {
            return replay(req);
        }
        UUID txId = newTx.get();

        Map<UUID, Account> locked = repo.lockAccounts(List.of(req.fromAccountId(), req.toAccountId()))
                .stream().collect(Collectors.toMap(Account::id, Function.identity()));
        Account from = require(locked, req.fromAccountId());
        Account to = require(locked, req.toAccountId());

        if (!from.currency().equals(to.currency())) {
            throw new IllegalArgumentException("Currency mismatch: " + from.currency() + " vs " + to.currency());
        }
        if (!from.allowNegative() && from.balance().compareTo(req.amount()) < 0) {
            throw new InsufficientFundsException(from.id());
        }

        repo.insertEntry(txId, from.id(), Direction.DEBIT, req.amount());
        repo.insertEntry(txId, to.id(), Direction.CREDIT, req.amount());
        repo.updateBalance(from.id(), from.balance().subtract(req.amount()));
        repo.updateBalance(to.id(), to.balance().add(req.amount()));
        repo.insertOutboxEvent(TransferCompleted.AGGREGATE, txId, TransferCompleted.TYPE,
                TransferCompleted.of(txId, req, repo.findOwners(List.of(req.fromAccountId(), req.toAccountId()))).toJson());
        return new TransferResult(txId, false);
    }

    @Transactional(readOnly = true)
    public Account getAccount(UUID accountId) {
        return repo.findAccount(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    /** Most recent ledger entries for an account, newest first. */
    @Transactional(readOnly = true)
    public List<LedgerEntry> getStatement(UUID accountId, int limit) {
        if (limit < 1 || limit > MAX_STATEMENT_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_STATEMENT_LIMIT);
        }
        return repo.findEntries(accountId, limit);
    }

    /** Compares cached balances with credits minus debits for every account. Empty means consistent. */
    @Transactional(readOnly = true)
    public List<BalanceMismatch> reconcile() {
        return repo.findBalanceMismatches();
    }

    @Transactional(readOnly = true)
    public List<BalanceMismatch> reconcile(Collection<UUID> accountIds) {
        return repo.findBalanceMismatches(accountIds);
    }

    private TransferResult replay(TransferRequest req) {
        StoredTransaction stored = repo.findTransactionByKey(req.idempotencyKey());
        if (stored.requestHash() != null && !stored.requestHash().equals(req.fingerprint())) {
            throw new IdempotencyKeyReuseException(req.idempotencyKey());
        }
        return new TransferResult(stored.id(), true);
    }

    private static Account require(Map<UUID, Account> accounts, UUID id) {
        Account a = accounts.get(id);
        if (a == null) throw new AccountNotFoundException(id);
        return a;
    }
}
