package com.portfolio.trading;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trade saga against a real Postgres and a fake ledger that can be told to fail. Recovery runs only when the test
 * calls it (its timer is set far in the future), so each step is deterministic.
 */
@SpringBootTest(properties = {
        "trading.recovery.min-age-ms=0", "trading.recovery.initial-delay-ms=3600000", "trading.recovery.interval-ms=3600000"})
@Import(TradeServiceIntegrationTest.FakeLedgerConfig.class)
@Testcontainers
class TradeServiceIntegrationTest {

    @TestConfiguration
    static class FakeLedgerConfig {
        @Bean
        @Primary
        FakeLedgerClient fakeLedger() {
            return new FakeLedgerClient();
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TradeService service;
    @Autowired TradeRecovery recovery;
    @Autowired TradingRepository trades;
    @Autowired FakeLedgerClient ledger;

    @BeforeEach
    void ledgerWorks() {
        ledger.mode = FakeLedgerClient.Mode.OK;
    }

    private static TradeRequest request(UUID account, Side side, String quantity) {
        return new TradeRequest("k-" + UUID.randomUUID(), account, "AAPL", side, new BigDecimal(quantity));
    }

    private BigDecimal held(UUID account) {
        return trades.findHoldings(account).stream().map(Holding::quantity).findFirst().orElse(BigDecimal.ZERO);
    }

    private static void assertQty(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void buy_completes_addsShares_andMovesCashOnce() {
        UUID account = ledger.fund("1000000");

        TradeResult result = service.execute(request(account, Side.BUY, "10"));

        assertEquals(TradeStatus.COMPLETED, result.trade().status());
        assertFalse(result.replayed());
        assertQty("10", held(account));
        assertEquals(1, ledger.callsInvolving(account).size());
        assertEquals("trade:" + result.trade().id(), ledger.callsInvolving(account).get(0).key());
        assertEquals(0, new BigDecimal("1000000").subtract(result.trade().notional()).compareTo(ledger.balance(account)));
    }

    @Test
    void sameKeyAgain_isAReplay_andDoesNotTouchTheLedger() {
        UUID account = ledger.fund("1000000");
        TradeRequest req = request(account, Side.BUY, "3");

        TradeResult first = service.execute(req);
        TradeResult second = service.execute(req);

        assertTrue(second.replayed());
        assertEquals(first.trade().id(), second.trade().id());
        assertEquals(1, ledger.callsInvolving(account).size());
        assertQty("3", held(account));
    }

    @Test
    void sameKeyWithADifferentRequest_isRefused() {
        UUID account = ledger.fund("1000000");
        String key = "reuse-" + UUID.randomUUID();
        service.execute(new TradeRequest(key, account, "AAPL", Side.BUY, new BigDecimal("1")));

        assertThrows(IdempotencyKeyReuseException.class,
                () -> service.execute(new TradeRequest(key, account, "AAPL", Side.BUY, new BigDecimal("2"))));
    }

    @Test
    void buyWithoutEnoughMoney_isRejected_andNothingChanges() {
        UUID account = ledger.fund("1");
        TradeRequest req = request(account, Side.BUY, "10");

        TradeRejectedException e = assertThrows(TradeRejectedException.class, () -> service.execute(req));
        assertEquals("INSUFFICIENT_FUNDS", e.code());

        assertQty("0", held(account));
        assertEquals(TradeStatus.REJECTED, trades.findTradeByKey(req.idempotencyKey()).orElseThrow().status());
        assertThrows(TradeRejectedException.class, () -> service.execute(req));   // replay gives the same verdict
    }

    @Test
    void sellingSharesYouDoNotHave_isRejected_beforeTheLedgerIsAsked() {
        UUID account = ledger.fund("1000");

        TradeRejectedException e = assertThrows(TradeRejectedException.class,
                () -> service.execute(request(account, Side.SELL, "5")));

        assertEquals("INSUFFICIENT_HOLDINGS", e.code());
        assertTrue(ledger.callsInvolving(account).isEmpty());
    }

    @Test
    void sell_completes_reducesShares_andCreditsCash() {
        UUID account = ledger.fund("1000000");
        service.execute(request(account, Side.BUY, "10"));
        BigDecimal cashAfterBuy = ledger.balance(account);

        TradeResult sell = service.execute(request(account, Side.SELL, "4"));

        assertEquals(TradeStatus.COMPLETED, sell.trade().status());
        assertQty("6", held(account));
        assertEquals(0, cashAfterBuy.add(sell.trade().notional()).compareTo(ledger.balance(account)));
    }

    @Test
    void sellRefusedByTheLedger_givesTheReservedSharesBack() {
        UUID account = ledger.fund("1000000");
        service.execute(request(account, Side.BUY, "10"));
        ledger.mode = FakeLedgerClient.Mode.REFUSE;

        TradeRejectedException e = assertThrows(TradeRejectedException.class,
                () -> service.execute(request(account, Side.SELL, "4")));

        assertEquals("LEDGER_REJECTED", e.code());
        assertQty("10", held(account));   // the compensating step restored them
    }

    @Test
    void ledgerDown_leavesTheTradePending_andRecoveryFinishesIt() {
        UUID account = ledger.fund("1000000");
        ledger.mode = FakeLedgerClient.Mode.DOWN;

        TradeResult pending = service.execute(request(account, Side.BUY, "10"));

        assertEquals(TradeStatus.PENDING, pending.trade().status());
        assertQty("0", held(account));   // shares are added only once the cash is confirmed

        ledger.mode = FakeLedgerClient.Mode.OK;
        recovery.recoverPending();

        assertEquals(TradeStatus.COMPLETED, trades.findTradeById(pending.trade().id()).orElseThrow().status());
        assertQty("10", held(account));
        assertEquals(0, new BigDecimal("1000000").subtract(pending.trade().notional()).compareTo(ledger.balance(account)));
    }

    @Test
    void lostLedgerResponse_isRetriedSafely_andCashMovesOnlyOnce() {
        UUID account = ledger.fund("1000000");
        ledger.mode = FakeLedgerClient.Mode.LOST_RESPONSE;   // the ledger applied it, but we never heard back

        TradeResult pending = service.execute(request(account, Side.BUY, "10"));
        assertEquals(TradeStatus.PENDING, pending.trade().status());

        ledger.mode = FakeLedgerClient.Mode.OK;
        recovery.recoverPending();   // retries with the same ledger key

        assertEquals(TradeStatus.COMPLETED, trades.findTradeById(pending.trade().id()).orElseThrow().status());
        assertQty("10", held(account));
        assertEquals(2, ledger.callsInvolving(account).size());   // asked twice...
        assertEquals(0, new BigDecimal("1000000").subtract(pending.trade().notional()).compareTo(ledger.balance(account)));   // ...applied once
    }

    @Test
    void clientRetryWithTheSameKey_resumesAPendingTrade() {
        UUID account = ledger.fund("1000000");
        TradeRequest req = request(account, Side.BUY, "2");
        ledger.mode = FakeLedgerClient.Mode.DOWN;
        assertEquals(TradeStatus.PENDING, service.execute(req).trade().status());

        ledger.mode = FakeLedgerClient.Mode.OK;
        TradeResult retry = service.execute(req);

        assertTrue(retry.replayed());
        assertEquals(TradeStatus.COMPLETED, retry.trade().status());
        assertQty("2", held(account));
    }

    @Test
    void sellWhilePending_keepsTheSharesReserved() {
        UUID account = ledger.fund("1000000");
        service.execute(request(account, Side.BUY, "10"));
        ledger.mode = FakeLedgerClient.Mode.DOWN;

        TradeResult pending = service.execute(request(account, Side.SELL, "10"));

        assertEquals(TradeStatus.PENDING, pending.trade().status());
        assertQty("0", held(account));   // reserved: cannot be sold twice
        assertThrows(TradeRejectedException.class, () -> service.execute(request(account, Side.SELL, "1")));
    }
}
