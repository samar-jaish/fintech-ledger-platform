# ADR 0018: UI screens, server data and idempotency keys

Status: Accepted (phase 5b)

## Context

Phase 5a gave the UI a login and one call. Phase 5b adds the real screens: accounts, money movement, trading. They read
data that changes on the server, and they send requests that move money, which must never be applied twice.

## Decision

- **A "my accounts" endpoint.** `GET /api/v1/accounts` returns the signed-in user's own accounts, oldest first. The API
  had no way to list accounts, only to fetch one by id. The query filters on the token's subject, so no one can list
  someone else's accounts; house accounts have no owner and never appear. An administrator also sees only their own here.
- **TanStack Query for server data.** It caches each answer under a key (`['account', id]`, `['statement', id]`, ...),
  shows loading and error states, and refetches. After a change (deposit, transfer, trade) the affected keys are
  invalidated, so balance, statement and portfolio update without manual refresh code. 4xx answers are not retried (the
  request is wrong); network failures and 5xx are retried twice. A portfolio with pending trades refreshes every 4 seconds
  until the ledger confirms them.
- **React Router.** The account page has two tabs, Money and Trading, as child routes, so the address says where you are and
  the back button works.
- **Idempotency keys chosen from the request content.** `useIdempotencyKey` gives the same key for an identical request
  and a new key when the content changes. If a transfer times out and the user presses Send again, the same key is sent and
  the API replays the first result instead of paying twice. If the user edits the amount first, a new key is used, because
  the API rejects one key for two different requests (409). After a success the key is dropped.
- **Amounts are validated in the browser for quick feedback** (positive, at most 4 decimals for money, 6 for shares) and
  validated again by the API. The browser check is a convenience; the API is the rule.
- **Not in this phase: live notifications.** The notification service has no API today (only its operations port), so a UI
  feed needs a small API with its own security and CORS first. That is phase 5d.

## Consequences

- Money formatting uses the browser's `Intl` and the account's currency code; an unknown code falls back to plain text.
- Account ids are shown in full on the account page so they can be shared for transfers; there is no user lookup by name.
  Looking recipients up by name would need another endpoint and is a product decision, not a technical one.
- The tests cover the screens against a fake API (62 tests). Full browser flows against the real stack come in phase 5c.
