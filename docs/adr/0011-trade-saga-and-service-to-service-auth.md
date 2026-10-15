# ADR 0011: Trading as its own service, trades as a saga, service-to-service auth

Status: Accepted

## Context

In Phase 1.5 a trade moved cash (ledger tables) and shares (holdings table) in one database transaction. With trading
in its own service and its own database, cash and shares live in two databases that cannot commit together. A trade
must still never lose money, never double-charge on a retry, and never leave shares and cash disagreeing for good.

## Decision

Trades are an orchestrated saga. The trading service is the orchestrator and stores the saga state in the `trades` row
(PENDING, COMPLETED or REJECTED).

- BUY: save the trade as PENDING, ask the ledger to move cash from the customer to the exchange account, then add the
  shares and mark COMPLETED.
- SELL: save the trade as PENDING and take the shares out of the position (reserved, so they cannot be sold twice), ask
  the ledger to move cash from the exchange to the customer, then mark COMPLETED.
- Compensation: if the ledger answers no (no funds, no account, refused) the trade is REJECTED. For a sell the reserved
  shares are given back. Nothing moved in the ledger, so nothing else needs undoing.
- Uncertainty: if the ledger cannot be reached or does not answer (timeout, connection error, 5xx), the outcome is
  unknown. The trade stays PENDING and the call is repeated with the same ledger idempotency key `trade:<trade id>`.
  The ledger applies a key once, so repeating is always safe, even if the first call actually succeeded.
- Retries come from three places: the client repeating the request with the same key, a scheduled recovery job that
  looks for old PENDING trades, and the next request for the same trade. All finish a trade through conditional updates
  that only succeed from PENDING, so shares are never added twice.
- The price is fetched before anything is stored, so no work is held open during a network call to the market.

Service-to-service calls:

- Who is calling is separate from who the customer is. For ownership checks the trading service calls the ledger with
  the customer's own token, so the ledger enforces ownership (404 for someone else's account) and there is no copy of
  ownership data to go stale.
- For moving money (a sell debits the exchange account, which no customer owns) the trading service logs in as itself
  with the OAuth2 client credentials grant and receives a token with the realm role SERVICE. Only `/api/v1/internal/**` on
  the ledger accepts it. The token is cached and renewed shortly before it expires.
- The ledger client is an interface with timeouts of 2 s (connect) and 5 s (read), so a slow ledger fails fast and the
  saga retries later.

## Consequences

- A customer can see a PENDING trade (HTTP 202). Repeating the same request later returns the final outcome.
- Portfolio cash (ledger) and positions (trading) are separate reads, so they can briefly disagree while a trade is
  pending; the portfolio reports `pendingTrades` to make that visible.
- A trade that stays PENDING forever (ledger permanently down) needs an alert and a manual decision; there is no
  automatic give-up yet.
- The trade id is not known to the ledger beyond the idempotency key, so reconciling the two services means joining on
  `trade:<id>` in the ledger transaction keys.
- The client credentials secret in the local realm is a demo value. Real deployments inject it.
- Phase 3c adds retries and a circuit breaker around the ledger client. The saga does not change, because it already
  treats "unavailable" as "retry later".
