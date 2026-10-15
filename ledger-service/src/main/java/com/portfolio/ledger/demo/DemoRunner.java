package com.portfolio.ledger.demo;

import com.portfolio.ledger.domain.Account;
import com.portfolio.ledger.domain.BalanceMismatch;
import com.portfolio.ledger.domain.InsufficientFundsException;
import com.portfolio.ledger.domain.TransferRequest;
import com.portfolio.ledger.domain.TransferResult;
import com.portfolio.ledger.service.LedgerService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Console demo, active only with the "demo" profile: opens accounts, moves money, replays a request with the same
 * idempotency key, tries an overdraft and reconciles. It writes to the configured database, so use the local
 * docker-compose one. Trading has its own service now (trading-service).
 */
@Component
@Profile("demo")
public class DemoRunner implements CommandLineRunner {

    private final LedgerService ledger;

    public DemoRunner(LedgerService ledger) {
        this.ledger = ledger;
    }

    @Override
    public void run(String... args) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        System.out.println();
        System.out.println("=== Ledger demo ===");

        Account house = ledger.openSystemAccount("DEMO-HOUSE-" + runId, "USD");
        Account alice = ledger.openAccount("Alice " + runId, "USD");
        Account bob = ledger.openAccount("Bob " + runId, "USD");

        ledger.transfer(new TransferRequest("demo-dep-" + runId, house.id(), alice.id(), new BigDecimal("1000.00"), "deposit"));
        System.out.println("Alice deposited 1000.00");

        TransferRequest payment = new TransferRequest("demo-pay-" + runId, alice.id(), bob.id(), new BigDecimal("250.00"), "rent");
        TransferResult first = ledger.transfer(payment);
        TransferResult again = ledger.transfer(payment);
        System.out.println("Alice paid Bob 250.00. Sending the same request again: replayed=" + again.replayed()
                + " (same transaction id: " + first.transactionId().equals(again.transactionId()) + ")");

        try {
            ledger.transfer(new TransferRequest("demo-over-" + runId, bob.id(), alice.id(), new BigDecimal("999.00"), "too much"));
        } catch (InsufficientFundsException e) {
            System.out.println("Bob tried to send 999.00 with only 250.00: rejected, balances unchanged");
        }

        System.out.println("Alice: " + ledger.getAccount(alice.id()).balance() + "   Bob: " + ledger.getAccount(bob.id()).balance());
        List<BalanceMismatch> drift = ledger.reconcile();
        System.out.println(drift.isEmpty()
                ? "Reconciliation: every cached balance matches its ledger entries."
                : "Reconciliation FAILED for " + drift.size() + " account(s).");
        System.out.println();
    }
}
