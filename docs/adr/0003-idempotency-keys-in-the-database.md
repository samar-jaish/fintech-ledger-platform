# ADR 0003: Idempotency enforced by a database unique key

Status: Accepted

## Context

Clients retry on timeouts, and duplicate requests can arrive simultaneously. A transfer must be applied at most once.

## Decision

Each transfer carries a client-supplied idempotency key. The key is inserted into `ledger_transactions`
(unique constraint) using `INSERT ... ON CONFLICT DO NOTHING` inside the business transaction, together with a SHA-256
fingerprint of the request (accounts and amount). A repeated key with the same fingerprint returns the original result;
a repeated key with a different fingerprint raises `IdempotencyKeyReuseException`.

## Consequences

- The unique index arbitrates races, so concurrent duplicates are applied once.
- A failed transfer rolls back its key, so it can be retried as a fresh attempt.
- Keys accumulate and need a retention policy in a production system.
- The same approach extends to idempotent message consumers once events are introduced.
