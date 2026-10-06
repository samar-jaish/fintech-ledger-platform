# Ledger Platform (Phase 1: Core Ledger)

A double-entry ledger engine in Java 21 + Spring Boot + PostgreSQL. Grows over 5 phases into an
AI-powered portfolio & analytics platform (see `docs/ROADMAP.md`).

## Run it
```bash
docker compose up -d        # Postgres on :5432
mvn test                    # unit tests (Mockito) + integration tests (Testcontainers, needs Docker)
mvn spring-boot:run         # Flyway applies db/migration on startup
```

## Phase 1 design decisions (your interview talking points)
| Decision | Why |
|---|---|
| `BigDecimal` + `NUMERIC(19,4)` | Floating point can't represent money exactly |
| Double-entry (DEBIT + CREDIT per transaction) | Money is never created or lost; every change is auditable |
| Append-only `ledger_entries` (DB trigger) | Corrections are new compensating entries, never edits |
| `SELECT ... ORDER BY id FOR UPDATE` | Pessimistic locking; fixed lock order prevents deadlocks |
| Idempotency key + `ON CONFLICT DO NOTHING` | Safe client retries; no double-spend |
| `CHECK (balance >= 0 OR allow_negative)` | The DB is the last line of defense, not just app code |
| Mockito for logic, Testcontainers for concurrency | Mocks can't prove locking or constraints work |

## Phase 1 exercises (do these yourself, they're the learning)
1. Replace pessimistic locking with optimistic locking (`version` column + retry). Compare throughput.
2. Make idempotent replays reject a *different* payload under the same key (store a request hash).
3. Add `EXPLAIN ANALYZE` on the statement query and show `idx_entries_account_ts` being used.
4. Add a reconciliation query: for every account, `balance == sum(credits) - sum(debits)`.
5. Add a Faker-based seeder and load 1M entries; note insert throughput.
