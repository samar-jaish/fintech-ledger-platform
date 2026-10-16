package com.portfolio.trading.ledger;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
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
