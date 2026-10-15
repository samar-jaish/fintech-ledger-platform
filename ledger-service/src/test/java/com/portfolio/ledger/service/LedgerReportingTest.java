package com.portfolio.ledger.service;

import com.portfolio.ledger.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

@SpringBootTest
@Testcontainers
class LedgerReportingTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired LedgerService service;
    @Autowired JdbcClient jdbc;

    private void deposit(Account house, Account to, String amount) {
        service.transfer(new TransferRequest("dep-" + UUID.randomUUID(), house.id(), to.id(),
                new BigDecimal(amount), "deposit"));
    }

    @Test
    void statement_returnsNewestFirst_andRespectsLimit() {
        Account house = service.openSystemAccount("house", "USD");
        Account alice = service.openAccount("alice", "USD");
        deposit(house, alice, "10");
        deposit(house, alice, "20");
        deposit(house, alice, "30");

        List<LedgerEntry> latestTwo = service.getStatement(alice.id(), 2);

        assertEquals(2, latestTwo.size());
        assertEquals(0, latestTwo.get(0).amount().compareTo(new BigDecimal("30")));
        assertEquals(0, latestTwo.get(1).amount().compareTo(new BigDecimal("20")));
        assertTrue(latestTwo.stream().allMatch(e -> e.direction() == Direction.CREDIT));
    }

    @Test
    void reconcile_isClean_afterConcurrentActivity() throws Exception {
        Account house = service.openSystemAccount("house", "USD");
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Account acc = service.openAccount("customer-" + i, "USD");
            deposit(house, acc, "1000");
            accounts.add(acc);
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            futures.add(pool.submit(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                int from = rnd.nextInt(accounts.size());
                int to = (from + 1 + rnd.nextInt(accounts.size() - 1)) % accounts.size();
                service.transfer(new TransferRequest("rnd-" + UUID.randomUUID(),
                        accounts.get(from).id(), accounts.get(to).id(),
                        new BigDecimal(1 + rnd.nextInt(10)), "random"));
            }));
        }
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        List<UUID> ids = new ArrayList<>(accounts.stream().map(Account::id).toList());
        ids.add(house.id());
        assertTrue(service.reconcile(ids).isEmpty());
    }

    @Test
    void reconcile_detectsBalanceTampering() {
        Account house = service.openSystemAccount("house", "USD");
        Account alice = service.openAccount("alice", "USD");
        deposit(house, alice, "100");

        // bypass the service on purpose
        jdbc.sql("UPDATE accounts SET balance = balance + 5 WHERE id = :id").param("id", alice.id()).update();

        List<BalanceMismatch> mismatches = service.reconcile(List.of(alice.id()));
        assertEquals(1, mismatches.size());
        assertEquals(alice.id(), mismatches.get(0).accountId());
        assertEquals(0, mismatches.get(0).difference().compareTo(new BigDecimal("5")));
    }
}
