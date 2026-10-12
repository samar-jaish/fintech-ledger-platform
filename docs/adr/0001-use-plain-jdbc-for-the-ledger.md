# ADR 0001: Use plain JDBC (JdbcClient) instead of JPA for the ledger

Status: Accepted

## Context

The ledger's correctness depends on explicit SQL behaviour: row locks (`SELECT ... FOR UPDATE`),
`INSERT ... ON CONFLICT`, conditional updates, constraints and set-based reporting queries.

## Decision

Persistence uses Spring's `JdbcClient` with hand-written SQL behind a `LedgerRepository` interface.

## Consequences

- Locking, isolation and constraint behaviour is visible in the code and easy to review.
- Bulk and reporting queries are straightforward and efficient.
- More mapping code than an ORM, and no automatic dirty checking.
- The repository interface keeps the service layer independent of the storage technology and easy to unit test.
- JPA may still be used for read-heavy, relationship-rich parts of future services.
