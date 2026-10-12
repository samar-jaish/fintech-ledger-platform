# ADR 0002: Pessimistic row locking by default, optimistic as an alternative

Status: Accepted

## Context

Concurrent transfers on the same account must never lose an update or overdraw a balance.
Popular accounts (for example house accounts) are written by many transactions at once.

## Decision

`LedgerService` locks both accounts with `SELECT ... ORDER BY id FOR UPDATE` in one statement, so locks are always
taken in the same order and opposite-direction transfers cannot deadlock. `OptimisticLedgerService` provides the same
behaviour using a version column and retries, so the two strategies can be compared with the benchmark suite.

## Consequences

- Correctness under contention without retry storms; waiting transactions hold a pooled connection while they wait.
- Optimistic locking performs well when contention is low and degrades on hot rows because of repeated retries.
- Every balance update increments the version, so both strategies stay compatible with each other's data.
- Keep transactions short: locks are held until commit.
