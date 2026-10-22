# ADR 0020: Live notifications in the UI

Status: accepted

## Context

The notification service only consumed Kafka events and wrote rows; nobody could read them. The UI should tell a user, as it
happens, that money arrived or left, and keep the numbers on screen current. That needs (1) a way to read a user's
notifications, (2) a way to push new ones to the browser, and (3) a way to know whose notification is whose.

## Decision

- **Server-sent events (SSE), not WebSockets.** Updates flow one way, server to browser, over plain HTTP. SSE is a text
  format on one long response, reconnects are simple, and it needs no extra protocol or library on the server
  (Spring's `SseEmitter`). WebSockets would add a second protocol and two-way traffic nobody needs.
- **The browser's `EventSource` cannot send an `Authorization` header**, so the UI reads the stream with `fetch` and a small
  parser (`ui/src/api/sse.ts`). Tokens stay in headers, never in URLs (URLs end up in logs).
- **Who a notification belongs to is carried by the event.** The ledger adds `fromOwner` and `toOwner` (the identity
  provider user ids, JWT `sub`) to `TransferCompleted`. The notification service stores them and filters by the token's
  subject. It never calls the ledger (no runtime dependency, works when the ledger is down), at the cost of a copy of
  that fact. Old consumers ignore the new fields (tolerant reader), and old rows without owners are shown to no one.
- **A user can only see their own.** There is no parameter for "another user" or "an account id". The service answers from the
  token alone, and owner ids are never put in the response; each item says SENT, RECEIVED or SELF from the caller's view.
- **Security is the same model as the other services:** stateless OAuth2 resource server, CORS only for the UI origins,
  fixed-text error bodies, operations endpoints on their own port. Any signed-in user may call it.
- **Ports.** The API is on 8084. The operations port keeps 8083, so probes, Prometheus and the Kubernetes Service do not change.
- **Streams are short-lived on purpose.** A stream is authenticated once, when it opens. The server ends it every 5 minutes
  (about an access token's lifetime) and the UI reconnects at once with a fresh token, so a user who signs out or is removed
  stops receiving within minutes. A comment line every 20 seconds keeps idle connections alive.
- **Reconnects lose nothing.** Each event carries the notification's database id. A reconnecting client sends `Last-Event-ID`
  and the server first replays what came after it (up to 100), then goes live. The server registers the stream before
  replaying, so nothing falls in the gap; the same event can then arrive twice, and the UI ignores ids it already has.
- **The database row is the truth, the push is a convenience.** A notification is stored first, then pushed. A failed push
  never fails the Kafka message.
- **The UI:** a bell in the header with an unread badge and a panel of recent activity. On each arrival it marks the account,
  statement, portfolio and trade queries stale, so balances change on screen without a reload.

## Consequences

- The push registry is in memory, in one process. With several instances of the notification service, Kafka gives each
  instance only some of the messages, so a user connected to instance A would miss events consumed by instance B. A real
  multi-instance setup needs a shared channel (for example Redis publish/subscribe, which is already in the platform, or
  one consumer group per instance so each instance sees every event). Not needed for one replica; noted for phase 6.
- Open streams are held by the server (one connection per signed-in tab). Fine at this scale; a large audience would need
  connection limits and a gateway built for long-lived connections.
- The notification carries a bare amount, not a currency, because the event does not; the UI shows the number only.
- Tests: the broadcaster and API are covered against a real Postgres (own-only listing, push to the right user, replay,
  CORS, limits); the parser, stream client and bell by unit tests; one browser test has two users in two sessions and shows
  a transfer appear for the receiver without a reload.
