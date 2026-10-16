# ADR 0012: Retries, circuit breaker and a fail-open quote cache

Status: Accepted

## Context

Trading depends on the ledger service for every trade and every ownership check. A slow or failing ledger would
otherwise hold request threads until each call times out, and a trade that hit one network blip would stay PENDING
until the recovery job picked it up. Separately, real market-data vendors rate-limit the free tiers, and many users
ask for the same symbols.

## Decision

- `ResilientLedgerClient` wraps the HTTP ledger client (decorator pattern, so the saga code is unchanged). It uses the
  Resilience4j core libraries directly, without Spring AOP annotations, so the behaviour is visible in one class.
- Retry: up to 3 attempts, waiting 200 ms then 400 ms. Only `LedgerUnavailableException` (timeout, connection error,
  5xx) is retried. Answers of "no" (insufficient funds, unknown account, rejected) are final and never retried.
  Retrying a transfer is safe because every attempt carries the same idempotency key (`trade:<id>`).
- Circuit breaker: looks at the last 10 calls and opens when at least 50 percent failed (after at least 5 calls).
  While open it rejects calls immediately with `LedgerUnavailableException`, for 10 seconds, then lets 2 test calls
  through (half-open) and closes again if they succeed. Answers of "no" count as healthy responses, so a flood of
  rejected trades cannot open the breaker.
- Retry wraps the breaker, so every attempt is counted, and a breaker that opens stops the remaining retries.
- Because an open breaker raises the same exception as a timeout, the saga needs no new case: the trade stays
  PENDING and the recovery job tries again later.
- Quote cache: `CachingMarketDataProvider` is a cache-aside decorator over any provider, backed by Redis with a
  5 second TTL, one key per provider and symbol. It is off by default (`MARKET_CACHE_ENABLED=true`), so the service
  runs without Redis.
- The cache fails open: any Redis error, timeout (200 ms) or unreadable value is treated as a cache miss. Trading
  never depends on Redis being up.

## Consequences

- A trade can use a price up to 5 seconds old. The trade stores the `asOf` time of the price it used, so this is
  visible, and it is acceptable for simulated trading. A real exchange would not cache prices used to fill orders.
- Worst case on the request path: 3 attempts with the 5 second read timeout is about 15 seconds before the caller gets
  a 503. Tune `ledger-client.resilience.*` and `spring.http.client.*` together.
- The breaker state is per trading-service instance. With several instances each learns on its own.
- Retries multiply load on a struggling ledger; the breaker is what stops that. A production system would add jitter
  to the backoff and metrics on breaker state.
- Only quotes are cached. Account balances and ownership checks are not: stale data there could allow a wrong decision.
