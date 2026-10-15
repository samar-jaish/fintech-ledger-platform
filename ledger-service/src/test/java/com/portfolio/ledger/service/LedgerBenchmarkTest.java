package com.portfolio.ledger.service;

import com.portfolio.ledger.domain.*;
import com.portfolio.ledger.repository.LedgerRepository;
import net.datafaker.Faker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seeds synthetic data and measures query and transfer performance.
 * Excluded from the default build. Run with:  mvn test -Pbenchmark
 *
 * Tunables (all optional), e.g.  mvn test -Pbenchmark -Dbench.seedTransactions=200000
 *   bench.customers          number of synthetic customer accounts   (default 10000)
 *   bench.seedTransactions   bulk-seeded transactions, 2 entries each (default 500000)
 *   bench.threads            worker threads                           (default 8)
 *   bench.transfers          transfers per thread, uniform scenario   (default 5000)
 */
@Tag("benchmark")
@SpringBootTest
@Testcontainers
class LedgerBenchmarkTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final int CUSTOMERS = Integer.getInteger("bench.customers", 10_000);
    static final int SEED_TX = Integer.getInteger("bench.seedTransactions", 500_000);
    static final int THREADS = Integer.getInteger("bench.threads", 8);
    static final int TRANSFERS = Integer.getInteger("bench.transfers", 5_000);

    @Autowired LedgerService pessimistic;
    @Autowired OptimisticLedgerService optimistic;
    @Autowired LedgerRepository repo;
    @Autowired JdbcClient jdbc;
    @Autowired JdbcTemplate jdbcTemplate;

    record Stats(String label, int ok, int insufficient, int failed, double seconds, long p50, long p95, long p99) { }

    @Test
    void runBenchmarks() throws Exception {
        Account house = seed();
        List<UUID> pool = jdbc.sql("SELECT id FROM accounts WHERE NOT allow_negative ORDER BY id LIMIT 2000")
                .query(UUID.class).list();

        System.out.println("\n===== Statement query plan =====");
        jdbc.sql("""
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT id, transaction_id, account_id, direction, amount, created_at
                FROM ledger_entries WHERE account_id = :a
                ORDER BY created_at DESC, id DESC LIMIT 20
                """).param("a", pool.get(0)).query(String.class).list().forEach(System.out::println);

        System.out.println("\n===== Statement latency (20 newest entries, 1000 random accounts) =====");
        List<Long> micros = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            UUID id = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            long begin = System.nanoTime();
            repo.findEntries(id, 20);
            micros.add((System.nanoTime() - begin) / 1_000);
        }
        Collections.sort(micros);
        System.out.printf("p50=%d us  p95=%d us  p99=%d us%n",
                percentile(micros, 50), percentile(micros, 95), percentile(micros, 99));

        List<Stats> results = new ArrayList<>();
        results.add(run("uniform / pessimistic", THREADS, TRANSFERS, i -> randomPair(pool), pessimistic::transfer));
        long c0 = optimistic.conflictCount();
        results.add(run("uniform / optimistic", THREADS, TRANSFERS, i -> randomPair(pool), optimistic::transfer));
        long uniformConflicts = optimistic.conflictCount() - c0;

        int hotPerThread = Math.max(100, TRANSFERS / 5);
        UUID hot = pool.get(0);
        BigDecimal fundHot = BigDecimal.valueOf(2L * THREADS * hotPerThread + 100);
        pessimistic.transfer(new TransferRequest("fund-hot-" + UUID.randomUUID(), house.id(), hot, fundHot, "fund hot account"));
        results.add(run("hot account / pessimistic", THREADS, hotPerThread, i -> fromHot(hot, pool), pessimistic::transfer));
        long c1 = optimistic.conflictCount();
        results.add(run("hot account / optimistic", THREADS, hotPerThread, i -> fromHot(hot, pool), optimistic::transfer));
        long hotConflicts = optimistic.conflictCount() - c1;

        System.out.println("\n===== Transfer throughput =====");
        System.out.printf("%-28s %7s %7s %7s %9s %8s %8s %8s%n",
                "scenario", "ok", "insuff", "failed", "tx/sec", "p50 ms", "p95 ms", "p99 ms");
        for (Stats s : results) {
            System.out.printf("%-28s %7d %7d %7d %9.0f %8.1f %8.1f %8.1f%n",
                    s.label(), s.ok(), s.insufficient(), s.failed(), s.ok() / s.seconds(),
                    s.p50() / 1e6, s.p95() / 1e6, s.p99() / 1e6);
        }
        System.out.println("optimistic conflicts retried: uniform=" + uniformConflicts + " hot=" + hotConflicts);

        assertTrue(pessimistic.reconcile().isEmpty(), "ledger must still reconcile after the benchmark");
    }

    // ---------- seeding ----------

    private Account seed() {
        Account house = repo.createAccount("HOUSE", "USD", true);
        Faker faker = new Faker(new Random(42));
        List<Object[]> rows = new ArrayList<>(CUSTOMERS);
        for (int i = 0; i < CUSTOMERS; i++) {
            rows.add(new Object[]{UUID.randomUUID(), faker.name().fullName(), "USD"});
        }
        jdbcTemplate.batchUpdate("INSERT INTO accounts (id, owner_name, currency) VALUES (?, ?, ?)", rows);

        long begin = System.nanoTime();
        // Bulk seed with set-based SQL: each transaction debits the house and credits one customer.
        jdbc.sql("""
                INSERT INTO ledger_transactions (id, idempotency_key, description, created_at)
                SELECT gen_random_uuid(), 'seed-' || g, 'bulk seed', now() - make_interval(secs => g)
                FROM generate_series(1, :n) AS g
                """).param("n", SEED_TX).update();
        jdbc.sql("""
                INSERT INTO ledger_entries (transaction_id, account_id, direction, amount, created_at)
                SELECT t.id, c.id, 'CREDIT', 10.0000, t.created_at
                FROM ledger_transactions t
                JOIN (SELECT id, row_number() OVER (ORDER BY id) AS rn FROM accounts WHERE NOT allow_negative) c
                  ON c.rn = 1 + (abs(hashtext(t.idempotency_key)::bigint) % :customers)
                WHERE t.description = 'bulk seed'
                """).param("customers", (long) CUSTOMERS).update();
        jdbc.sql("""
                INSERT INTO ledger_entries (transaction_id, account_id, direction, amount, created_at)
                SELECT t.id, :house, 'DEBIT', 10.0000, t.created_at
                FROM ledger_transactions t WHERE t.description = 'bulk seed'
                """).param("house", house.id()).update();
        jdbc.sql("""
                UPDATE accounts a SET balance = s.bal
                FROM (SELECT account_id, SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END) AS bal
                      FROM ledger_entries GROUP BY account_id) s
                WHERE s.account_id = a.id
                """).update();
        jdbc.sql("ANALYZE accounts").update();
        jdbc.sql("ANALYZE ledger_transactions").update();
        jdbc.sql("ANALYZE ledger_entries").update();
        double secs = (System.nanoTime() - begin) / 1e9;
        System.out.printf("Seeded %d customers and %d entries in %.1f s%n", CUSTOMERS, 2L * SEED_TX, secs);
        return house;
    }

    // ---------- request factories ----------

    private static TransferRequest randomPair(List<UUID> pool) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int from = rnd.nextInt(pool.size());
        int to = (from + 1 + rnd.nextInt(pool.size() - 1)) % pool.size();
        return new TransferRequest("b-" + UUID.randomUUID(), pool.get(from), pool.get(to),
                new BigDecimal("1.00"), "benchmark");
    }

    private static TransferRequest fromHot(UUID hot, List<UUID> pool) {
        UUID to;
        do {
            to = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        } while (to.equals(hot));
        return new TransferRequest("h-" + UUID.randomUUID(), hot, to, new BigDecimal("1.00"), "benchmark hot");
    }

    // ---------- harness ----------

    private Stats run(String label, int threads, int perThread,
                      java.util.function.IntFunction<TransferRequest> factory,
                      Consumer<TransferRequest> op) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Queue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(executor.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    TransferRequest req = factory.apply(i);
                    long begin = System.nanoTime();
                    try {
                        op.accept(req);
                        ok.incrementAndGet();
                    } catch (InsufficientFundsException e) {
                        insufficient.incrementAndGet();
                    } catch (RuntimeException e) {
                        failed.incrementAndGet();
                    }
                    latencies.add(System.nanoTime() - begin);
                }
                return null;
            }));
        }
        long begin = System.nanoTime();
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.MINUTES);
        }
        double seconds = (System.nanoTime() - begin) / 1e9;
        executor.shutdown();

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        return new Stats(label, ok.get(), insufficient.get(), failed.get(), seconds,
                percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99));
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.min(sorted.size() - 1, Math.ceil(p / 100.0 * sorted.size()) - 1);
        return sorted.get(Math.max(index, 0));
    }
}
