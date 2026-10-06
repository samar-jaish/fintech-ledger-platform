package com.portfolio.ledger.service;

import com.portfolio.ledger.domain.*;
import com.portfolio.ledger.repository.LedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LedgerService {

    private final LedgerRepository repo;

    public LedgerService(LedgerRepository repo) {
        this.repo = repo;
    }

    public Account openAccount(String ownerName, String currency) {
        return repo.createAccount(ownerName, currency, false);
    }

    /** House account used as the counterparty for deposits/withdrawals. May go negative. */
    public Account openSystemAccount(String name, String currency) {
        return repo.createAccount(name, currency, true);
    }

    /**
     * Double-entry transfer: one DEBIT + one CREDIT of the same amount, atomically.
     * Idempotent per idempotencyKey. Uses pessimistic row locks on both accounts.
     */
    @Transactional
    public TransferResult transfer(TransferRequest req) {
        Optional<UUID> newTx = repo.insertTransaction(req.idempotencyKey(), req.description());
        if (newTx.isEmpty()) {
            return new TransferResult(repo.findTransactionIdByKey(req.idempotencyKey()), true);
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
        return new TransferResult(txId, false);
    }

    private static Account require(Map<UUID, Account> accounts, UUID id) {
        Account a = accounts.get(id);
        if (a == null) throw new AccountNotFoundException(id);
        return a;
    }
}
