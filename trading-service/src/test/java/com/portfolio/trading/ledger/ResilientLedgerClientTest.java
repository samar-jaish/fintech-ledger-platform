package com.portfolio.trading.ledger;

import com.portfolio.trading.AccountNotFoundException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Retry and circuit breaker behaviour against a scripted ledger. No Spring, no network. */
class ResilientLedgerClientTest {

    /** Fails with the given exception for the first {@code failures} calls, then succeeds. Records every call. */
    static class ScriptedLedger implements LedgerClient {
        int failures;
        RuntimeException failure = new LedgerUnavailableException("down");
        int calls;
        final List<String> transferKeys = new ArrayList<>();

        private void maybeFail() {
            calls++;
            if (failures > 0) {
                failures--;
                throw failure;
            }
        }

        @Override
        public AccountView getAccount(UUID accountId, String bearerToken) {
            maybeFail();
            return new AccountView(accountId, "Alice", "USD", BigDecimal.TEN);
        }

        @Override
        public UUID exchangeAccountId(String currency) {
            maybeFail();
            return UUID.randomUUID();
        }

        @Override
        public void transfer(String key, UUID from, UUID to, BigDecimal amount, String description) {
            transferKeys.add(key);
            maybeFail();
        }
    }

    private static ResilienceSettings settings(int attempts, int window, int minCalls, Duration open) {
        return new ResilienceSettings(attempts, Duration.ofMillis(1), window, minCalls, 50f, open, 1);
    }

    private static void transfer(LedgerClient client, String key) {
        client.transfer(key, UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE, "t");
    }

    @Test
    void retriesATransientFailureAndSucceeds() {
        ScriptedLedger ledger = new ScriptedLedger();
        ledger.failures = 2;
        ResilientLedgerClient client = new ResilientLedgerClient(ledger, settings(3, 100, 100, Duration.ofSeconds(10)));

        transfer(client, "trade:1");

        assertThat(ledger.calls).isEqualTo(3);
    }

    @Test
    void givesUpAfterTheMaximumNumberOfAttempts() {
        ScriptedLedger ledger = new ScriptedLedger();
        ledger.failures = 10;
        ResilientLedgerClient client = new ResilientLedgerClient(ledger, settings(3, 100, 100, Duration.ofSeconds(10)));

        assertThatThrownBy(() -> transfer(client, "trade:1")).isInstanceOf(LedgerUnavailableException.class);
        assertThat(ledger.calls).isEqualTo(3);
    }

    @Test
    void everyRetryUsesTheSameIdempotencyKey() {
        ScriptedLedger ledger = new ScriptedLedger();
        ledger.failures = 2;
        ResilientLedgerClient client = new ResilientLedgerClient(ledger, settings(3, 100, 100, Duration.ofSeconds(10)));

        transfer(client, "trade:abc");

        assertThat(ledger.transferKeys).containsExactly("trade:abc", "trade:abc", "trade:abc");
    }

    @Test
    void doesNotRetryAnAnswerOfNo() {
        ScriptedLedger ledger = new ScriptedLedger();
        ledger.failures = 5;
        ledger.failure = new InsufficientFundsException(UUID.randomUUID());
        ResilientLedgerClient client = new ResilientLedgerClient(ledger, settings(3, 100, 100, Duration.ofSeconds(10)));

        assertThatThrownBy(() -> transfer(client, "trade:1")).isInstanceOf(InsufficientFundsException.class);
        assertThat(ledger.calls).isEqualTo(1);

        ledger.failures = 5;
        ledger.failure = new AccountNotFoundException(UUID.randomUUID());
        assertThatThrownBy(() -> client.getAccount(UUID.randomUUID(), "token")).isInstanceOf(AccountNotFoundException.class);
        assertThat(ledger.calls).isEqualTo(2);
    }

    @Test
    void opensAfterRepeatedFailuresAndThenStopsCallingTheLedger() {
        ScriptedLedger ledger = new ScriptedLedger();
        ledger.failures = 100;
        ResilientLedgerClient client = new ResilientLedgerClient(ledger, settings(1, 4, 4, Duration.ofSeconds(30)));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> transfer(client, "trade:x")).isInstanceOf(LedgerUnavailableException.class);
        }
        assertThat(client.state()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsBefore = ledger.calls;

        assertThatThrownBy(() -> transfer(client, "trade:x"))
                .isInstanceOf(LedgerUnavailableException.class)
                .hasMessageContaining("circuit breaker is open");
        assertThat(ledger.calls).isEqualTo(callsBefore);   // rejected without touching the ledger
    }

    @Test
    void answersOfNoNeverOpenTheBreaker() {
        ScriptedLedger ledger = new ScriptedLedger();
        ledger.failures = 100;
        ledger.failure = new InsufficientFundsException(UUID.randomUUID());
        ResilientLedgerClient client = new ResilientLedgerClient(ledger, settings(1, 4, 4, Duration.ofSeconds(30)));

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> transfer(client, "trade:x")).isInstanceOf(InsufficientFundsException.class);
        }

        assertThat(client.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void closesAgainWhenTheLedgerRecovers() throws InterruptedException {
        ScriptedLedger ledger = new ScriptedLedger();
        ledger.failures = 4;
        ResilientLedgerClient client = new ResilientLedgerClient(ledger, settings(1, 4, 4, Duration.ofMillis(50)));
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> transfer(client, "trade:x")).isInstanceOf(LedgerUnavailableException.class);
        }
        assertThat(client.state()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(120);   // longer than the open duration: the next call is a test call
        transfer(client, "trade:y");   // the ledger works again

        assertThat(client.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
