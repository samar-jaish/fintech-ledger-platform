package com.portfolio.ledger.service;

import com.portfolio.ledger.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class LedgerDeadlockTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired LedgerService service;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager txManager;

    private Account funded(Account house, String amount) {
        Account acc = service.openAccount("acc-" + UUID.randomUUID(), "USD");
        service.transfer(new TransferRequest("fund-" + acc.id(), house.id(), acc.id(),
                new BigDecimal(amount), "deposit"));
        return acc;
    }

    private BigDecimal balance(UUID id) {
        return jdbc.sql("SELECT balance FROM accounts WHERE id = :id").param("id", id)
                .query(BigDecimal.class).single();
    }

    /** The real service locks in id order, so A to B and B to A can never deadlock. */
    @Test
    void oppositeTransfers_neverDeadlock() throws Exception {
        Account house = service.openSystemAccount("house", "USD");
        Account a = funded(house, "1000");
        Account b = funded(house, "1000");
        int perDirection = 100;

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < perDirection; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                service.transfer(new TransferRequest("ab-" + UUID.randomUUID(), a.id(), b.id(),
                        new BigDecimal("1.00"), "a to b"));
                return null;
            }));
            futures.add(pool.submit(() -> {
                start.await();
                service.transfer(new TransferRequest("ba-" + UUID.randomUUID(), b.id(), a.id(),
                        new BigDecimal("1.00"), "b to a"));
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS); // any deadlock error surfaces here
        }
        pool.shutdown();

        assertEquals(0, balance(a.id()).compareTo(new BigDecimal("1000")));
        assertEquals(0, balance(b.id()).compareTo(new BigDecimal("1000")));
    }

    /**
     * Demonstration: locking two rows one at a time in opposite order DOES deadlock.
     * Postgres detects it (after deadlock_timeout, 1s by default) and aborts exactly one transaction.
     */
    @Test
    void naiveLocking_inOppositeOrder_deadlocks() throws Exception {
        Account house = service.openSystemAccount("house", "USD");
        Account a = funded(house, "100");
        Account b = funded(house, "100");
        CyclicBarrier bothHoldFirstLock = new CyclicBarrier(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<String>> tasks = List.of(
                () -> lockInOrder(a.id(), b.id(), bothHoldFirstLock),
                () -> lockInOrder(b.id(), a.id(), bothHoldFirstLock));
        List<Future<String>> results = pool.invokeAll(tasks);
        pool.shutdown();

        int succeeded = 0;
        int deadlocked = 0;
        for (Future<String> f : results) {
            try {
                f.get();
                succeeded++;
            } catch (ExecutionException e) {
                if (isDeadlock(e)) {
                    deadlocked++;
                } else {
                    throw e;
                }
            }
        }
        assertEquals(1, deadlocked, "Postgres should abort exactly one of the two");
        assertEquals(1, succeeded);
    }

    private String lockInOrder(UUID first, UUID second, CyclicBarrier barrier) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            lock(first);
            await(barrier);
            lock(second);
        });
        return "done";
    }

    private void lock(UUID id) {
        jdbc.sql("SELECT id FROM accounts WHERE id = :id FOR UPDATE").param("id", id)
                .query(UUID.class).single();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isDeadlock(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "40P01".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
