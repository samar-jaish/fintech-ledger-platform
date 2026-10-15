package com.portfolio.ledger.batch;

import com.portfolio.ledger.domain.Account;
import com.portfolio.ledger.domain.SystemAccounts;
import com.portfolio.ledger.domain.TransferRequest;
import com.portfolio.ledger.service.LedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real job against a real Postgres. Ledger entries are append-only and stamped with now(), so
 * "today" shows balances including today's entries, and a day long ago shows an empty ledger.
 * Other tests share the database, so assertions look only at the accounts created here.
 */
@SpringBootTest
@Testcontainers
class NightlyCloseIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final LocalDate LONG_AGO = LocalDate.of(2000, 1, 1);

    @Autowired NightlyCloseService closeService;
    @Autowired DailyBalanceRepository balances;
    @Autowired LedgerService ledger;
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    private Account fundedAccount(String amount) {
        Account a = ledger.openAccount("batch-" + UUID.randomUUID(), "USD");
        ledger.transfer(new TransferRequest("batch-dep-" + UUID.randomUUID(), SystemAccounts.HOUSE_USD,
                a.id(), new BigDecimal(amount), "test funding"));
        return a;
    }

    @Test
    void closingToday_recordsBalanceEntryCountAndReconciled() {
        Account a = fundedAccount("250.00");
        LocalDate today = LocalDate.now(clock);

        closeService.run(today, true);

        DailyBalance row = balances.find(a.id(), today).orElseThrow();
        assertEquals(0, new BigDecimal("250.00").compareTo(row.closingBalance()));
        assertEquals(0, new BigDecimal("250.00").compareTo(row.cachedBalance()));
        assertEquals(1, row.entryCount());
        assertTrue(row.reconciled());
    }

    @Test
    void closingADayBeforeAnyActivity_givesZeroBalance() {
        Account a = fundedAccount("100.00");

        closeService.run(LONG_AGO, true);

        DailyBalance row = balances.find(a.id(), LONG_AGO).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(row.closingBalance()));
        assertEquals(0, row.entryCount());
        assertTrue(row.reconciled(), "cached balance still matches the full ledger");
    }

    @Test
    void sameDateTwice_isRefused_butRerunIsAllowedAndKeepsOneRowPerAccount() {
        Account a = fundedAccount("10.00");
        LocalDate day = LocalDate.of(2001, 2, 3);

        closeService.run(day, false);
        assertThrows(NightlyCloseConflictException.class, () -> closeService.run(day, false));

        closeService.run(day, true);
        long rows = jdbc.sql("SELECT count(*) FROM daily_balances WHERE account_id = :id AND business_date = :d")
                .param("id", a.id()).param("d", day).query(Long.class).single();
        assertEquals(1, rows);
    }

    @Test
    void aCorruptedCachedBalance_isFlaggedAndReported() {
        Account a = fundedAccount("75.00");
        LocalDate day = LocalDate.of(2002, 3, 4);
        try {
            jdbc.sql("UPDATE accounts SET balance = balance + 1 WHERE id = :id").param("id", a.id()).update();

            NightlyCloseResult result = closeService.run(day, true);

            assertFalse(balances.find(a.id(), day).orElseThrow().reconciled());
            assertEquals("COMPLETED_WITH_MISMATCHES", result.exitCode());
            assertTrue(result.mismatches() >= 1);
        } finally {
            jdbc.sql("UPDATE accounts SET balance = balance - 1 WHERE id = :id").param("id", a.id()).update();
        }
    }

    @Test
    void aFutureDate_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> closeService.run(LocalDate.now(clock).plusDays(1), false));
    }
}
