# Ledger Platform

A double-entry ledger engine built with Java 21, Spring Boot and PostgreSQL. It focuses on the
hard part of payments software: moving money correctly under concurrency, retries and partial failure.

## Features

- Double-entry accounting: every transfer is one debit and one credit of equal value, recorded in an append-only ledger.
- Exact money handling with `BigDecimal` and `NUMERIC(19,4)`.
- Idempotent transfers via client-supplied keys. A repeated key returns the original result; reusing a key with a different payload is rejected.
- Concurrency-safe balance updates with two interchangeable strategies: pessimistic row locking (default) and optimistic locking with retries.
- Deadlock-free locking through a fixed lock order.
- Defence in depth: balance, amount and direction rules are enforced by database constraints, and a trigger makes the ledger immutable.
- Account statements (newest entries first) and a reconciliation query that detects drift between cached balances and ledger entries.
- Versioned schema migrations with Flyway.

## Tech stack

Java 21, Spring Boot 3.5 (JDBC), PostgreSQL 16, Flyway, JUnit 5, Mockito, Testcontainers, Datafaker, Docker Compose, GitHub Actions.

## Getting started

Prerequisites: JDK 21, Maven 3.9+, and Docker.

```bash
docker compose up -d     # PostgreSQL on localhost:5432 (database/user/password: ledger)
mvn verify               # unit tests (Mockito) and integration tests (Testcontainers)
mvn spring-boot:run      # applies migrations on startup
```

## Testing

- Unit tests run the business logic against a mocked repository.
- Integration tests run against a real PostgreSQL container and cover race conditions (50 concurrent withdrawals), idempotent retries, deadlock avoidance, database constraints, statements and reconciliation.
- Integration tests need Docker running.

## Benchmarks

An opt-in benchmark seeds synthetic data (Datafaker) and compares the two locking strategies, plus statement query latency and its query plan:

```bash
mvn test -Pbenchmark
mvn test -Pbenchmark -Dbench.seedTransactions=200000 -Dbench.threads=4   # smaller run
```

## Design

Architecture decisions are recorded in [docs/adr](docs/adr). The planned evolution of the project is in [docs/ROADMAP.md](docs/ROADMAP.md).

### Data model

- `accounts`: current state, including a cached balance and a version for optimistic locking.
- `ledger_transactions`: one row per business event, keyed by a unique idempotency key and a request fingerprint.
- `ledger_entries`: immutable debit and credit lines, indexed for per-account statements.

## Project structure

```
src/main/java/com/portfolio/ledger
  domain/        records, enums and exceptions (no persistence code)
  repository/    LedgerRepository interface and JDBC implementation
  service/       LedgerService (pessimistic), OptimisticLedgerService
src/main/resources/db/migration   Flyway migrations
src/test/java                     unit, integration and benchmark tests
```
