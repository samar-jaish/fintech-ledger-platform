package com.portfolio.trading.ledger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The breaker and retry numbers that dashboards read, checked against a scripted ledger. No Spring, no network. */
class LedgerClientMetricsTest {

    private static ResilienceSettings quickBreaker() {
        // 1 attempt (no retries), opens after 2 failed calls, stays open long enough for the test to look at it
        return new ResilienceSettings(1, Duration.ofMillis(1), 4, 2, 50f, Duration.ofSeconds(30), 1);
    }

    private static void transferIgnoringFailure(ResilientLedgerClient client) {
        try {
            client.transfer("k-" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE, "test");
        } catch (LedgerUnavailableException expected) {
            // the scripted ledger is down on purpose
        }
    }

    @Test
    void breakerState_andRefusedCalls_areVisibleAsMetrics() {
        var ledger = new ResilientLedgerClientTest.ScriptedLedger();
        ledger.failures = 100;   // every call fails
        var client = new ResilientLedgerClient(ledger, quickBreaker());
        var registry = new SimpleMeterRegistry();
        client.bindMetrics(registry);

        assertThat(registry.get("ledger.client.circuit.state").gauge().value()).isEqualTo(0.0);   // closed

        transferIgnoringFailure(client);
        transferIgnoringFailure(client);                                                          // 2 failures: opens
        assertThat(registry.get("ledger.client.circuit.state").gauge().value()).isEqualTo(2.0);   // open

        transferIgnoringFailure(client);                                                          // refused at once
        assertThat(registry.get("ledger.client.circuit.not.permitted").functionCounter().count()).isEqualTo(1.0);
        assertThat(ledger.calls).isEqualTo(2);   // the refused call never reached the ledger
    }

    @Test
    void aCallThatNeededARetry_isCounted() {
        var ledger = new ResilientLedgerClientTest.ScriptedLedger();
        ledger.failures = 1;     // first attempt fails, the retry succeeds
        var settings = new ResilienceSettings(3, Duration.ofMillis(1), 10, 5, 50f, Duration.ofSeconds(30), 1);
        var client = new ResilientLedgerClient(ledger, settings);
        var registry = new SimpleMeterRegistry();
        client.bindMetrics(registry);

        client.transfer("k-" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE, "test");

        assertThat(registry.get("ledger.client.retry.calls").tag("outcome", "succeeded_after_retry")
                .functionCounter().count()).isEqualTo(1.0);
    }
}
