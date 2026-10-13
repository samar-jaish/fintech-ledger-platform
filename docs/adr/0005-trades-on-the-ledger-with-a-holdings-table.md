# ADR 0005: Trades are ledger transfers plus a holdings table

Status: Accepted

## Context

A trade moves cash and changes a position. Both must succeed or fail together, and cash must stay auditable.

## Decision

Cash moves as a normal double-entry transfer between the customer and a house EXCHANGE account. The position lives in
a `holdings` table (average-cost method) and the trade in `trades` (unique idempotency key). All three are written in
one database transaction. Overspending and overselling are prevented by the ledger's balance check and a guarded
`UPDATE ... WHERE quantity >= :qty`.

## Consequences

- Trades are serialized on the EXCHANGE row (the hot-row effect measured in the Phase 1 benchmark); sharding the
  exchange account is the usual fix.
- Realized profit and loss is not tracked yet; only unrealized P&L is reported.
