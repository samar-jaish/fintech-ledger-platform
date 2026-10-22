package com.portfolio.ledger.service;

import com.portfolio.ledger.domain.*;
import com.portfolio.ledger.repository.LedgerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Runs against a real Postgres (Testcontainers). Needs Docker running. */
@SpringBootTest
@Testcontainers
class LedgerConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired LedgerService service;
    @Autowired LedgerRepository repo;
    @Autowired JdbcClient jdbc;

    private BigDecimal balance(UUID id) {
        return repo.findAccount(id).orElseThrow().balance();
    }

    private Account fundedAccount(Account house, String amount) {
        Account acc = service.openAccount("alice-" + UUID.randomUUID(), "USD");
        service.transfer(new TransferRequest("fund-" + acc.id(), house.id(), acc.id(), new BigDecimal(amount), "deposit"));
        return acc;
    }

    @Test
    void concurrentTransfers_neverOverdraw_andMoneyIsConserved() throws Exception {
        Account house = service.openSystemAccount("house", "USD");
        Account a = fundedAccount(house, "1000.00");
        Account b = service.openAccount("bob", "USD");

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "t-" + UUID.randomUUID();
            results.add(pool.submit(() -> {
                start.await();
                try {
                    service.transfer(new TransferRequest(key, a.id(), b.id(), new BigDecimal("100.00"), "race"));
                    return true;
                } catch (InsufficientFundsException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        long ok = 0;
        for (Future<Boolean> f : results) if (f.get(30, TimeUnit.SECONDS)) ok++;
        pool.shutdown();

        assertEquals(10, ok, "exactly 1000/100 transfers can succeed");
        assertEquals(0, balance(a.id()).compareTo(BigDecimal.ZERO));
        assertEquals(0, balance(b.id()).compareTo(new BigDecimal("1000")));
        // Conservation: sum of all balances for these accounts is zero (house is -1000)
        BigDecimal sum = balance(house.id()).add(balance(a.id())).add(balance(b.id()));
        assertEquals(0, sum.compareTo(BigDecimal.ZERO));
    }

    @Test
    void sameIdempotencyKey_sentConcurrently_appliesOnce() throws Exception {
        Account house = service.openSystemAccount("house", "USD");
        Account a = fundedAccount(house, "1000.00");
        Account b = service.openAccount("bob", "USD");

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        TransferRequest req = new TransferRequest("same-key-" + UUID.randomUUID(), a.id(), b.id(),
                new BigDecimal("100.00"), "retry storm");
        List<Future<TransferResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> { start.await(); return service.transfer(req); }));
        }
        start.countDown();
        long applied = 0;
        for (Future<TransferResult> f : results) if (!f.get(30, TimeUnit.SECONDS).replayed()) applied++;
        pool.shutdown();

        assertEquals(1, applied);
        assertEquals(0, balance(a.id()).compareTo(new BigDecimal("900")));
        assertEquals(0, balance(b.id()).compareTo(new BigDecimal("100")));
    }

    @Test
    void ledgerEntries_areAppendOnly() {
        Account house = service.openSystemAccount("house", "USD");
        fundedAccount(house, "10.00");
        assertThrows(DataAccessException.class,
                () -> jdbc.sql("UPDATE ledger_entries SET amount = 1").update());
        assertThrows(DataAccessException.class,
                () -> jdbc.sql("DELETE FROM ledger_entries").update());
    }

    @Test
    void findOwners_returnsTheOwnerOfUserAccountsAndOmitsHouseAccounts() {
        Account house = service.openSystemAccount("house", "USD");
        Account mine = repo.createAccount("dana", "USD", false, "user-dana");

        var owners = repo.findOwners(List.of(house.id(), mine.id()));

        assertEquals(1, owners.size());
        assertEquals("user-dana", owners.get(mine.id()));
    }

    @Test
    void database_rejectsNegativeBalanceOnCustomerAccount() {
        Account a = service.openAccount("carol", "USD");
        assertThrows(DataAccessException.class,
                () -> repo.updateBalance(a.id(), new BigDecimal("-1.00")));
    }

    @Test
    void sameKeyWithDifferentPayload_isRejected() {
        Account house = service.openSystemAccount("house", "USD");
        Account a = fundedAccount(house, "1000.00");
        Account b = service.openAccount("bob", "USD");
        String key = "reuse-" + UUID.randomUUID();

        service.transfer(new TransferRequest(key, a.id(), b.id(), new BigDecimal("100.00"), "first"));

        assertThrows(IdempotencyKeyReuseException.class, () ->
                service.transfer(new TransferRequest(key, a.id(), b.id(), new BigDecimal("200.00"), "second")));
        assertEquals(0, balance(a.id()).compareTo(new BigDecimal("900")));
    }
}
