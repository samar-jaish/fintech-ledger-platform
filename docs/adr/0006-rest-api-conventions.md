# ADR 0006: REST API conventions

Status: Accepted

## Context

The API is the first thing a client or interviewer touches. It must behave predictably under retries and failures.

## Decision

- Resources under `/api/v1`. Money and quantities are JSON numbers read into BigDecimal; responses never use floating point arithmetic.
- Every write that moves money or shares requires an `Idempotency-Key` header, passed to the service layer. A replay returns 200 with `replayed: true`; a first execution returns 201.
- Errors are RFC 7807 problem details with a stable machine-readable `code`. Status mapping: 400 invalid input, 404 unknown account, 409 key reused with a different request, 422 business rule (insufficient funds or holdings), 503 market data down.
- Unexpected errors return a generic 500 and are logged. Vendor and internal messages are never sent to clients.
- Controllers are thin: they validate and map. Business rules and transactions stay in the services.
- Deposits come from a well-known house account created by a migration. There are no real payment rails.

## Consequences

- Clients can retry safely and branch on `code`.
- Authentication and authorization are not part of this change and are added in the next step.
