package com.portfolio.ledger.trading;

import com.portfolio.ledger.domain.*;
import com.portfolio.ledger.repository.LedgerRepository;
import com.portfolio.ledger.service.LedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Trades against a real Postgres, using the default simulated market (no network). */
@SpringBootTest
@Testcontainers
class TradingIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TradeService trading;
    @Autowired PortfolioService portfolio;
    @Autowired LedgerService ledger;
    @Autowired TradingRepository tradingRepo;
    @Autowired LedgerRepository ledgerRepo;

    private BigDecimal balance(Account a) {
        return ledgerRepo.findAccount(a.id()).orElseThrow().balance();
    }

    private Account funded(String amount) {
        Account house = ledger.openSystemAccount("house-" + UUID.randomUUID(), "USD");
        Account investor = ledger.openAccount("investor-" + UUID.randomUUID(), "USD");
        ledger.transfer(new TransferRequest("dep-" + UUID.randomUUID(), house.id(), investor.id(),
                new BigDecimal(amount), "deposit"));
        return investor;
    }

    private TradeRequest buy(Account a, String key, String symbol, String qty) {
        return new TradeRequest(key, a.id(), symbol, Side.BUY, new BigDecimal(qty));
    }

    @Test
    void buyThenSell_movesCashAndShares_andLedgerStillReconciles() {
        Account investor = funded("100000");
        TradeResult bought = trading.execute(buy(investor, "b-" + UUID.randomUUID(), "AAPL", "10"));
        assertFalse(bought.replayed());
        assertEquals("simulated", bought.trade().priceSource());

        TradeResult sold = trading.execute(new TradeRequest("s-" + UUID.randomUUID(), investor.id(), "AAPL",
                Side.SELL, new BigDecimal("4")));
        assertFalse(sold.replayed());

        Holding h = tradingRepo.findHoldings(investor.id()).get(0);
        assertEquals(0, h.quantity().compareTo(new BigDecimal("6")));

        BigDecimal expectedCash = new BigDecimal("100000")
                .subtract(bought.trade().notional()).add(sold.trade().notional());
        assertEquals(0, balance(investor).compareTo(expectedCash));
        assertTrue(ledger.reconcile().isEmpty());
    }

    @Test
    void sameKey_isReplayed_andExecutesOnce() {
        Account investor = funded("100000");
        String key = "dup-" + UUID.randomUUID();
        TradeResult first = trading.execute(buy(investor, key, "MSFT", "2"));
        TradeResult second = trading.execute(buy(investor, key, "MSFT", "2"));
        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(first.trade().id(), second.trade().id());
        assertEquals(0, tradingRepo.findHoldings(investor.id()).get(0).quantity().compareTo(new BigDecimal("2")));
    }

    @Test
    void sameKey_withDifferentRequest_isRejected() {
        Account investor = funded("100000");
        String key = "reuse-" + UUID.randomUUID();
        trading.execute(buy(investor, key, "MSFT", "2"));
        assertThrows(IdempotencyKeyReuseException.class, () -> trading.execute(buy(investor, key, "MSFT", "3")));
    }

    @Test
    void insufficientFunds_rollsBackEverything() {
        Account investor = funded("1");
        assertThrows(InsufficientFundsException.class, () -> trading.execute(buy(investor, "poor-" + UUID.randomUUID(), "AAPL", "10")));
        assertTrue(tradingRepo.findHoldings(investor.id()).isEmpty());
        assertTrue(tradingRepo.findTrades(investor.id(), 10).isEmpty());
        assertEquals(0, balance(investor).compareTo(BigDecimal.ONE));
    }

    @Test
    void sellingMoreThanHeld_isRejected_andRollsBackTheCashMovement() {
        Account investor = funded("100000");
        trading.execute(buy(investor, "b-" + UUID.randomUUID(), "AAPL", "1"));
        BigDecimal cashBefore = balance(investor);

        assertThrows(InsufficientHoldingsException.class, () -> trading.execute(
                new TradeRequest("s-" + UUID.randomUUID(), investor.id(), "AAPL", Side.SELL, new BigDecimal("5"))));

        assertEquals(0, balance(investor).compareTo(cashBefore));
        assertEquals(1, tradingRepo.findTrades(investor.id(), 10).size());
    }

    @Test
    void sameKeyFromManyThreads_executesExactlyOnce() throws Exception {
        Account investor = funded("100000");
        String key = "race-" + UUID.randomUUID();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger fresh = new AtomicInteger();
        var futures = new java.util.ArrayList<Future<?>>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                if (!trading.execute(buy(investor, key, "AAPL", "1")).replayed()) {
                    fresh.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1, fresh.get());
        assertEquals(1, tradingRepo.findTrades(investor.id(), 10).size());
    }

    @Test
    void portfolio_valuesPositions_withSourceAndCompleteTotals() {
        Account investor = funded("100000");
        trading.execute(buy(investor, "b-" + UUID.randomUUID(), "AAPL", "10"));
        Portfolio p = portfolio.getPortfolio(investor.id());
        assertTrue(p.complete());
        assertEquals(1, p.positions().size());
        assertEquals("simulated", p.positions().get(0).priceSource());
        assertEquals(0, p.totalValue().compareTo(p.cash().add(p.totalMarketValue())));
    }

    @Test
    void unknownAccount_isRejected() {
        assertThrows(AccountNotFoundException.class,
                () -> trading.execute(new TradeRequest("x-" + UUID.randomUUID(), UUID.randomUUID(), "AAPL", Side.BUY, BigDecimal.ONE)));
    }
}
