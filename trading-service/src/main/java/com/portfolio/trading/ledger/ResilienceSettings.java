package com.portfolio.trading.ledger;

import java.time.Duration;

/**
 * Tuning for retries and the circuit breaker around the ledger client (properties under ledger-client.resilience.*).
 *
 * @param maxAttempts      total tries per call, including the first
 * @param initialBackoff   wait before the second try; doubles for each further try
 * @param windowSize       how many recent calls the breaker looks at
 * @param minimumCalls     the breaker never opens before this many calls have been seen
 * @param failureRatePercent open the breaker when at least this percentage of the window failed
 * @param openDuration     how long the breaker rejects calls before it lets a few through to test recovery
 * @param halfOpenCalls    how many test calls are allowed while recovering
 */
public record ResilienceSettings(int maxAttempts, Duration initialBackoff, int windowSize, int minimumCalls,
                                 float failureRatePercent, Duration openDuration, int halfOpenCalls) {

    public static ResilienceSettings defaults() {
        return new ResilienceSettings(3, Duration.ofMillis(200), 10, 5, 50f, Duration.ofSeconds(10), 2);
    }
}
