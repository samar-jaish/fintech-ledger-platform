package com.portfolio.ledger.service;

import com.portfolio.ledger.domain.*;
import com.portfolio.ledger.outbox.TransferCompleted;
import com.portfolio.ledger.repository.LedgerRepository;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Same transfer semantics as {@link LedgerService}, but using optimistic locking:
 * accounts are read without locks and written with a version check. A lost race
 * rolls the whole transaction back and the transfer is retried from scratch.
 *
 * The retry loop must live OUTSIDE the transaction (each attempt gets a fresh one),
 * which is why this class uses TransactionTemplate instead of @Transactional.
 */
@Service
public class OptimisticLedgerService {

    private static final int MAX_ATTEMPTS = 100;

    private final LedgerRepository repo;
    private final TransactionTemplate tx;
    private final AtomicLong conflicts = new AtomicLong();

    public OptimisticLedgerService(LedgerRepository repo, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.tx = new TransactionTemplate(txManager);
    }

    public TransferResult transfer(TransferRequest req) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> attemptTransfer(req));
            } catch (ConcurrencyFailureException e) {
                // OptimisticLockingFailureException, deadlock loser and serialization failures all land here
                conflicts.incrementAndGet();
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                backoff(attempt);
            }
        }
    }

    /** Number of retried conflicts since startup, for comparing strategies. */
    public long conflictCount() {
        return conflicts.get();
    }

    private TransferResult attemptTransfer(TransferRequest req) {
        Optional<UUID> newTx = repo.insertTransaction(req.idempotencyKey(), req.description(), req.fingerprint());
        if (newTx.isEmpty()) {
            StoredTransaction stored = repo.findTransactionByKey(req.idempotencyKey());
            if (stored.requestHash() != null && !stored.requestHash().equals(req.fingerprint())) {
                throw new IdempotencyKeyReuseException(req.idempotencyKey());
            }
            return new TransferResult(stored.id(), true);
        }
        UUID txId = newTx.get();

        Map<UUID, VersionedAccount> accounts = repo.findVersioned(List.of(req.fromAccountId(), req.toAccountId()))
                .stream().collect(Collectors.toMap(v -> v.account().id(), Function.identity()));
        VersionedAccount fromV = require(accounts, req.fromAccountId());
        VersionedAccount toV = require(accounts, req.toAccountId());
        Account from = fromV.account();
        Account to = toV.account();

        if (!from.currency().equals(to.currency())) {
            throw new IllegalArgumentException("Currency mismatch: " + from.currency() + " vs " + to.currency());
        }
        if (!from.allowNegative() && from.balance().compareTo(req.amount()) < 0) {
            throw new InsufficientFundsException(from.id());
        }

        repo.insertEntry(txId, from.id(), Direction.DEBIT, req.amount());
        repo.insertEntry(txId, to.id(), Direction.CREDIT, req.amount());

        // Apply the conditional updates in a fixed id order so two competing transfers
        // cannot deadlock on each other's row locks.
        List<VersionedAccount> ordered = Stream.of(fromV, toV)
                .sorted(Comparator.comparing((VersionedAccount v) -> v.account().id()))
                .toList();
        for (VersionedAccount v : ordered) {
            BigDecimal newBalance = v.account().id().equals(from.id())
                    ? from.balance().subtract(req.amount())
                    : to.balance().add(req.amount());
            if (!repo.updateBalanceIfVersion(v.account().id(), newBalance, v.version())) {
                throw new OptimisticLockingFailureException("Account " + v.account().id() + " changed concurrently");
            }
        }
        repo.insertOutboxEvent(TransferCompleted.AGGREGATE, txId, TransferCompleted.TYPE,
                TransferCompleted.of(txId, req).toJson());
        return new TransferResult(txId, false);
    }

    private static void backoff(int attempt) {
        long maxMillis = 2L * Math.min(attempt, 10);
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, maxMillis + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off", e);
        }
    }

    private static VersionedAccount require(Map<UUID, VersionedAccount> accounts, UUID id) {
        VersionedAccount a = accounts.get(id);
        if (a == null) throw new AccountNotFoundException(id);
        return a;
    }
}
