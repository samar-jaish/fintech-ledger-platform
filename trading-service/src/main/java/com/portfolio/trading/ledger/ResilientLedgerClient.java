package com.portfolio.trading.ledger;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Wraps another LedgerClient with retries and a circuit breaker. The saga code does not know it is there.
 *
 * Which failures count: only LedgerUnavailableException (timeouts, connection errors, 5xx) is retried and counted
 * against the breaker. "No" answers from the ledger (insufficient funds, unknown account, rejected) mean the ledger is
 * healthy, so they are neither retried nor counted as failures.
 *
 * Retrying a transfer is safe because the idempotency key is the same on every attempt: the ledger applies it once.
 * When the breaker is open, calls fail at once with LedgerUnavailableException instead of waiting for timeouts, so
 * the saga leaves the trade PENDING and the recovery job tries again later.
 *
 * Order: retry wraps breaker, so each attempt is seen by the breaker, and a breaker that opens mid-retry stops further tries.
 */
public class ResilientLedgerClient implements LedgerClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientLedgerClient.class);

    private final LedgerClient delegate;
    private final Retry retry;
    private final CircuitBreaker breaker;

    public ResilientLedgerClient(LedgerClient delegate, ResilienceSettings s) {
        this.delegate = delegate;
        this.breaker = CircuitBreaker.of("ledger", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(s.windowSize())
                .minimumNumberOfCalls(s.minimumCalls())
                .failureRateThreshold(s.failureRatePercent())
                .waitDurationInOpenState(s.openDuration())
                .permittedNumberOfCallsInHalfOpenState(s.halfOpenCalls())
                .recordExceptions(LedgerUnavailableException.class)
                .build());
        this.retry = Retry.of("ledger", RetryConfig.custom()
                .maxAttempts(s.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(s.initialBackoff(), 2.0))
                .retryExceptions(LedgerUnavailableException.class)
                .build());
        breaker.getEventPublisher().onStateTransition(e ->
                log.warn("Ledger circuit breaker: {}", e.getStateTransition()));
        retry.getEventPublisher().onRetry(e ->
                log.info("Retrying ledger call (attempt {} failed): {}", e.getNumberOfRetryAttempts(), e.getLastThrowable().getMessage()));
    }

    /**
     * Publishes the breaker and retry numbers as metrics, so a dashboard shows when the ledger is struggling:
     * the breaker state (0 closed, 1 half-open, 2 open), the failure rate it sees, calls it refused while open, and
     * how often a retry was needed. Reads the live Resilience4j objects, so there is nothing to keep in sync.
     */
    public void bindMetrics(MeterRegistry registry) {
        Gauge.builder("ledger.client.circuit.state", breaker, b -> stateNumber(b.getState()))
                .description("Ledger circuit breaker: 0 closed (normal), 1 half-open (testing), 2 open (rejecting calls)")
                .register(registry);
        Gauge.builder("ledger.client.circuit.failure.rate", breaker, b -> b.getMetrics().getFailureRate())
                .description("Percent of recent ledger calls that failed (-1 until enough calls were seen)")
                .register(registry);
        FunctionCounter.builder("ledger.client.circuit.not.permitted", breaker, b -> b.getMetrics().getNumberOfNotPermittedCalls())
                .description("Calls refused immediately because the breaker was open")
                .register(registry);
        FunctionCounter.builder("ledger.client.retry.calls", retry, r -> r.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt())
                .tag("outcome", "succeeded_after_retry")
                .description("Ledger calls that needed a retry")
                .register(registry);
        FunctionCounter.builder("ledger.client.retry.calls", retry, r -> r.getMetrics().getNumberOfFailedCallsWithRetryAttempt())
                .tag("outcome", "failed_after_retry")
                .description("Ledger calls that needed a retry")
                .register(registry);
    }

    private static double stateNumber(CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED -> 0;
            case HALF_OPEN -> 1;
            case OPEN -> 2;
            default -> 3;   // DISABLED, FORCED_OPEN, METRICS_ONLY: not used here
        };
    }

    /** CLOSED (normal), OPEN (rejecting calls) or HALF_OPEN (testing recovery). For logs and tests. */
    public CircuitBreaker.State state() {
        return breaker.getState();
    }

    @Override
    public AccountView getAccount(UUID accountId, String bearerToken) {
        return call(() -> delegate.getAccount(accountId, bearerToken));
    }

    @Override
    public UUID exchangeAccountId(String currency) {
        return call(() -> delegate.exchangeAccountId(currency));
    }

    @Override
    public void transfer(String idempotencyKey, UUID fromAccountId, UUID toAccountId, BigDecimal amount, String description) {
        call(() -> {
            delegate.transfer(idempotencyKey, fromAccountId, toAccountId, amount, description);
            return null;
        });
    }

    private <T> T call(Supplier<T> operation) {
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(breaker, operation);
        try {
            return Retry.decorateSupplier(retry, guarded).get();
        } catch (CallNotPermittedException e) {
            throw new LedgerUnavailableException("Ledger circuit breaker is open: not calling the ledger", e);
        }
    }
}
