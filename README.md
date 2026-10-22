![CI](https://github.com/samar-jaish/fintech-ledger-platform/actions/workflows/ci.yml/badge.svg)

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
- Three services (ledger, trading, notification) communicating over REST and Kafka, each with its own database; trades run as a saga with compensation.
- Retries and a circuit breaker around service calls; a fail-open Redis cache for market quotes.

## Market data and trading

Prices come from a provider chosen by `MARKET_PROVIDER`: `simulated` (default, no network), `replay` (prices recorded in
the database) or `live` (needs `FINNHUB_API_KEY` and `TWELVEDATA_API_KEY`; free keys). Every price shows its source.
Never commit API keys.

Trading is its own service (`trading-service`, port 8082, its own database). Cash lives in the ledger service and shares
in the trading service, so a trade is a saga: the trade is saved as PENDING, the ledger is asked to move the cash (safe to
repeat, thanks to idempotency keys), and then the position is updated and the trade marked COMPLETED. If the ledger refuses,
the trade is REJECTED and any reserved shares are returned. If the ledger cannot be reached, the trade stays PENDING and is
retried automatically. Design notes: [ADR 0011](docs/adr/0011-trade-saga-and-service-to-service-auth.md).

Calls from trading to the ledger go through retries (3 tries with growing waits) and a circuit breaker: after repeated
failures the breaker opens and calls fail immediately instead of waiting for timeouts, then it tests the ledger again
after 10 seconds. Quotes can be cached in Redis for a few seconds (`MARKET_CACHE_ENABLED=true`); if Redis is down the
service simply asks the provider. Design notes: [ADR 0012](docs/adr/0012-resilience-and-quote-cache.md).

Console demo of the ledger: `mvn -pl ledger-service spring-boot:run -Dspring-boot.run.profiles=demo` (needs `docker compose up -d`).

## REST API

Start the database (`docker compose up -d`), then `mvn -pl ledger-service spring-boot:run`. The API listens on port 8080.

| Method and path | Purpose |
|---|---|
| `POST /api/v1/accounts` | Open an account (`ownerName`, `currency`) |
| `GET /api/v1/accounts` | The signed-in user's own accounts, oldest first |
| `GET /api/v1/accounts/{id}` | Balance |
| `POST /api/v1/accounts/{id}/deposits` | Simulated cash-in from the house account (USD, header `Idempotency-Key`) |
| `POST /api/v1/transfers` | Move money between accounts (header `Idempotency-Key`) |
| `GET /api/v1/accounts/{id}/statement?limit=50` | Ledger entries, newest first |
| `GET /api/v1/reconciliation` | Cached balances versus ledger entries (role `ADMIN`) |

Trading endpoints are on the trading service (port 8082), same tokens and error format:

| Method and path | Purpose |
|---|---|
| `POST /api/v1/trades` | Market order: `accountId`, `symbol`, `side` BUY or SELL, `quantity` (header `Idempotency-Key`). 201 completed, 200 replay, 202 pending (ledger not confirmed yet), 422 rejected |
| `GET /api/v1/accounts/{id}/portfolio` | Positions valued at current prices, with price source and number of pending trades |
| `GET /api/v1/accounts/{id}/trades?limit=50` | Trade history with status |

The ledger also has `/api/v1/internal/**` endpoints for other services (role `SERVICE`, obtained through the OAuth2
client credentials flow). They are not for end users.

Writes require an `Idempotency-Key` header. A repeated key returns the original result with 200 instead of 201.
Errors use RFC 7807 (`application/problem+json`) with a stable `code`: 400 INVALID_REQUEST, 404 ACCOUNT_NOT_FOUND,
409 IDEMPOTENCY_KEY_REUSED, 422 INSUFFICIENT_FUNDS or INSUFFICIENT_HOLDINGS, 503 MARKET_DATA_UNAVAILABLE.
## Authentication and authorization

The API is an OAuth2 resource server. Every request needs `Authorization: Bearer <JWT>` issued by Keycloak, which runs
locally in Docker with a pre-loaded realm `ledger` (development settings and demo users only):

| User | Password | Roles |
|---|---|---|
| alice | alice123 | USER |
| bob | bob123 | USER |
| admin1 | admin123 | USER, ADMIN |

- A USER can use only the accounts they opened. Someone else's account answers 404, the same as a missing one.
- `GET /api/v1/reconciliation` and everything under `/api/v1/admin/` need ADMIN. An ADMIN can read any account.
- Missing or invalid token: 401. Wrong role: 403. Both use the same problem+json format as other errors.
- Tokens are validated against `KEYCLOAK_ISSUER_URI` (default `http://localhost:8180/realms/ledger`).
- Deposits are a simulated cash-in so the API can be tried end to end; a real system would not allow users to mint money.

Run everything: `docker compose up -d` (wait about a minute for Keycloak), then `mvn -pl ledger-service spring-boot:run`.

Example (PowerShell):

```
$tokenUrl = "http://localhost:8180/realms/ledger/protocol/openid-connect/token"
$tok = (Invoke-RestMethod -Method Post $tokenUrl -Body @{grant_type="password"; client_id="ledger-api"; username="alice"; password="alice123"}).access_token
$auth = @{Authorization="Bearer $tok"}
$id = (Invoke-RestMethod -Method Post http://localhost:8080/api/v1/accounts -Headers $auth -ContentType application/json -Body '{"ownerName":"Alice","currency":"USD"}').id
Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/accounts/$id/deposits" -Headers ($auth + @{"Idempotency-Key"="d1"}) -ContentType application/json -Body '{"amount":1000}'
$trade = @{accountId=$id; symbol="AAPL"; side="BUY"; quantity=5} | ConvertTo-Json
Invoke-RestMethod -Method Post http://localhost:8082/api/v1/trades -Headers ($auth + @{"Idempotency-Key"="t1"}) -ContentType application/json -Body $trade
Invoke-RestMethod "http://localhost:8082/api/v1/accounts/$id/portfolio" -Headers $auth
```

Access tokens are short-lived (minutes by default); fetch a new one when you get 401.

## Nightly close (Spring Batch)

A batch job closes each UTC day: for every account it rebuilds the closing balance from the ledger entries, compares
the cached balance with the sum of all entries, and stores one row per account per day in `daily_balances`.

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/admin/nightly-close` | ADMIN only. Body `{"businessDate":"2025-06-30","rerun":false}`. Runs the job and returns the result |
| `GET /api/v1/accounts/{id}/daily-balances?limit=30` | End-of-day history for an account (owner or ADMIN), newest first |

- Closing a date that is already closed returns 409 `NIGHTLY_CLOSE_CONFLICT`; send `"rerun":true` to close it again.
- If any account fails reconciliation the job still completes, with exit code `COMPLETED_WITH_MISMATCHES`.
- Automatic run: set `NIGHTLY_CLOSE_CRON` (for example `0 30 0 * * *`, UTC) to close the previous day. Off by default.
- Design notes: [ADR 0008](docs/adr/0008-nightly-close-with-spring-batch.md).

A Postman collection for the API is in [postman/](postman).

## Events (outbox and Kafka)

Every committed transfer also writes a `TransferCompleted` event to an `outbox_events` table in the same database
transaction. A relay publishes those rows to the Kafka topic `ledger.transfers`, so no event is lost if the process
dies after the commit, and none is sent for a transfer that rolled back. Delivery is at-least-once.

The `notification-service` module is a separate application with its own database. It consumes the events, records
one notification per event (ignoring duplicate deliveries), and sends messages it cannot read to a dead-letter topic
(`ledger.transfers.DLT`) after a few retries. Design notes: [ADR 0009](docs/adr/0009-transactional-outbox-and-kafka.md),
[ADR 0010](docs/adr/0010-separate-notification-service.md).

The notification service also has a small API on port 8084, secured like the others (bearer tokens from Keycloak, CORS for
the web UI): `GET /api/v1/notifications` lists the signed-in user's recent notifications, and
`GET /api/v1/notifications/stream` is a live stream (server-sent events) of new ones. A user only ever sees notifications
about their own accounts: the ledger puts the two owners' ids in each transfer event, and the service filters on the token's
subject. A client that reconnects sends `Last-Event-ID` and first receives what it missed. The web UI shows this as a bell in
the header and refreshes balances when something arrives. Design notes: [ADR 0020](docs/adr/0020-live-notifications.md).

Run it: `docker compose up -d`, then start the app with `OUTBOX_RELAY_ENABLED=true`
(PowerShell: `$env:OUTBOX_RELAY_ENABLED="true"; mvn -pl ledger-service spring-boot:run`) and, in a second terminal, the notification service
(`mvn -pl notification-service spring-boot:run`). Make a transfer or deposit: the ledger log shows the relay
publishing, and the notification service log shows `NOTIFY: Transfer of ...`. The relay is off by default so the
ledger runs without Kafka.

## Tech stack

Java 21, Spring Boot 3.5 (JDBC), PostgreSQL 16, Flyway, Redis, Resilience4j, JUnit 5, Mockito, Testcontainers, Datafaker, Docker Compose, GitHub Actions, Micrometer, Prometheus, Grafana, OpenTelemetry, Jaeger, Kubernetes (kind, Kustomize).

## Getting started

Prerequisites: JDK 21, Maven 3.9+, and Docker.

```bash
docker compose up -d     # PostgreSQL (databases ledger, notifications, trading), Keycloak, Kafka, Redis
mvn verify               # builds every module: unit tests (Mockito) and integration tests (Testcontainers)
mvn -pl ledger-service spring-boot:run        # ledger API, applies migrations on startup
mvn -pl trading-service spring-boot:run       # trades and portfolio (needs the ledger running)
mvn -pl notification-service spring-boot:run  # optional: consumes transfer events
```

### Tools to install on your computer

Docker runs the servers (Postgres, Kafka and so on), but these tools run on your computer: Java 21 and Maven (build and
tests), Docker Desktop, Node 22+ (web UI), and for Kubernetes `kind` and `kubectl`. The smoke test also needs `curl` and `jq`
(Git Bash on Windows provides `curl`; install `jq` with `winget install jqlang.jq`).

### Everything in containers

```bash
docker compose --profile apps up -d --build   # builds an image per service and starts the whole platform
docker compose --profile apps logs -f ledger-service trading-service
docker compose --profile apps down            # stop (add -v to also delete the databases)
```

The services reach each other by name (`ledger-service`, `postgres`, `kafka`, `keycloak`, `redis`), and the ledger relay and
the quote cache are switched on. `docker compose up -d` without the profile still starts only the infrastructure, which is
what the tests and running from the IDE use. Design notes: [ADR 0013](docs/adr/0013-container-images-and-compose.md).

### Continuous integration

Every push and pull request runs three stages on GitHub Actions: all unit and integration tests, then a smoke test that
starts the whole platform with Docker Compose and sends real requests (login, deposit, trade, replay, access rules and the
Kafka event path, health, metrics, the Grafana dashboard and cross-service traces, see `scripts/smoke-test.sh`), and, on `master` only, publishing the four images (three services and the UI) to the GitHub
Container Registry (`ghcr.io/samar-jaish/fintech-ledger-platform/<service>`, tagged `latest` and with the commit id).
Design notes: [ADR 0014](docs/adr/0014-ci-smoke-test-and-image-publishing.md).

### Observability

```bash
docker compose --profile apps --profile observability up -d --build
```

| What | Where |
| --- | --- |
| Grafana dashboard "Ledger platform" | http://localhost:3000 (admin / admin, or just view) |
| Prometheus (targets, alert rules) | http://localhost:9090 |
| Jaeger (traces) | http://localhost:16686 |
| Health: ledger, trading, notification | http://localhost:9080, :9082, :8083 + `/actuator/health/readiness` |
| Notification API (list, live stream) | http://localhost:8084/api/v1/notifications (needs a bearer token) |
| Raw metrics | http://localhost:9080/actuator/prometheus (and :9082, :8083) |

Every service reports health (liveness and readiness, where ready means "can reach its database"), JVM, HTTP, database pool
and Kafka metrics, and its own business numbers: trades by outcome, pending trades and how long the oldest has waited, the
ledger circuit breaker state and retries, events waiting in the outbox, notifications stored or ignored as duplicates, and
dead-lettered messages. Six alert rules (service down, 5xx rate, breaker open, trades stuck pending, outbox backlog, dead
letters) are evaluated by Prometheus. Requests are traced across services, so one trade shows as a single trace through
trading-service and ledger-service. The health and metrics endpoints use a separate management port that is not part of
the public API. Design notes: [ADR 0015](docs/adr/0015-observability.md).

### Kubernetes (local)

The same platform runs on a local Kubernetes cluster made by [kind](https://kind.sigs.k8s.io). Needs Docker, `kind` and
`kubectl`; stop Docker Compose first because both use the same host ports.

```bash
bash scripts/kind-up.sh      # creates the cluster, builds and loads the images, deploys everything, waits until ready
bash scripts/smoke-test.sh   # same end-to-end check as in CI (set NOTIFICATION_LOGS_CMD="kubectl -n ledger logs deploy/notification-service")
kubectl -n ledger get pods
bash scripts/kind-down.sh    # deletes the cluster
```

The manifests in `k8s/base` are cluster-neutral (Deployments with startup, liveness and readiness probes, resource requests
and limits, a non-root read-only container, rolling updates that never reduce capacity, a StatefulSet for Postgres, Services,
a ConfigMap and a Secret). `k8s/overlays/kind` adds what is specific to the local cluster: NodePorts published on this
computer and the local image tag. The API is at http://localhost:8080 and :8082, Keycloak at :8180, Jaeger at :16686.
CI creates a kind cluster and runs the smoke test against it too. Design notes: [ADR 0016](docs/adr/0016-kubernetes-with-kind.md).

### Web UI

A React + TypeScript app (Vite) in `ui/`. Sign in goes through Keycloak (authorization code flow with PKCE), then the
screens call the APIs with your access token: your accounts and balances, add money, send money, statement, portfolio, buy
and sell, and trade history. Every money-moving form sends an idempotency key, so a retry cannot apply twice. Needs Node 22 or newer.

```bash
docker compose --profile apps up -d --build   # the platform (see above)
cd ui
npm install
npm run dev                                   # http://localhost:5173
npm test                                      # unit and component tests (no browser or network needed)
```

The UI also ships as a container (nginx serving the compiled files, no Node at run time). It is part of the `apps` profile
and of the kind cluster, so after `docker compose --profile apps up -d --build` the finished app is at **http://localhost:8085**.
Browser tests (Playwright, a real Chromium) sign in at Keycloak and run a journey through the whole platform; CI runs them
after the smoke test:

```bash
cd ui
npx playwright install chromium   # once
npm run e2e                       # needs the platform running; UI_URL=... to point elsewhere
```

Sign in as `alice` / `alice123`. The APIs allow calls from `http://localhost:5173` (CORS); see
[ADR 0017](docs/adr/0017-web-ui-and-browser-login.md) and
[ADR 0018](docs/adr/0018-ui-screens-data-and-idempotency.md) for the screens. If you started Keycloak before the UI client existed, recreate it so it
reads the realm file again: `docker compose up -d --force-recreate keycloak`.

## Testing

- Unit tests run the business logic against a mocked repository.
- Integration tests run against a real PostgreSQL container and cover race conditions (50 concurrent withdrawals), idempotent retries, deadlock avoidance, database constraints, statements and reconciliation.
- Integration tests need Docker running.

## Benchmarks

An opt-in benchmark seeds synthetic data (Datafaker) and compares the two locking strategies, plus statement query latency and its query plan:

```bash
mvn test -Pbenchmark -pl ledger-service
mvn test -Pbenchmark -pl ledger-service -Dbench.seedTransactions=200000 -Dbench.threads=4   # smaller run
```

Sample results (8 threads, PostgreSQL 16 in Docker on a laptop, 1M ledger entries, single run):

| Scenario | tx/sec | p50 | p95 | p99 |
|---|---|---|---|---|
| Uniform accounts, pessimistic | 1217 | 6.1 ms | 8.6 ms | 12.7 ms |
| Uniform accounts, optimistic | 1186 | 6.2 ms | 9.6 ms | 14.7 ms |
| Single hot account, pessimistic | 321 | 24.1 ms | 27.9 ms | 32.5 ms |
| Single hot account, optimistic | 230 | 12.4 ms | 147.2 ms | 262.9 ms |

- Under low contention the two strategies perform alike (about 1% of optimistic transfers needed a retry).
- On a single hot account, optimistic locking averaged about 2.8 retries per successful transfer and had a much heavier latency tail, which is why pessimistic locking is the default (see [ADR 0002](docs/adr/0002-pessimistic-locking-by-default.md)).
- The per-account statement query uses the composite index `idx_entries_account_ts`: about 0.06 ms server-side over 1M entries, and about 0.8 ms p50 end to end.
- The ledger reconciled with zero drift after roughly 96,000 benchmark transfers.

Figures come from a single run on a development laptop and are indicative, not absolute.

## Design

Architecture decisions are recorded in [docs/adr](docs/adr). The planned evolution of the project is in [docs/ROADMAP.md](docs/ROADMAP.md).

### Data model

- `accounts`: current state, including a cached balance and a version for optimistic locking.
- `ledger_transactions`: one row per business event, keyed by a unique idempotency key and a request fingerprint.
- `ledger_entries`: immutable debit and credit lines, indexed for per-account statements.

## Project structure

```
ledger-service/            ledger, REST API, security, nightly close, outbox publisher
  src/main/java/com/portfolio/ledger
    domain/        records, enums and exceptions (no persistence code)
    repository/    LedgerRepository interface and JDBC implementation
    service/       LedgerService (pessimistic), OptimisticLedgerService
    api/           controllers, security, error handling
    batch/         nightly close job (Spring Batch), daily balance snapshots
    outbox/        transfer events: outbox table and Kafka relay
  src/main/resources/db/migration   Flyway migrations
  src/test/java                     unit, integration and benchmark tests
trading-service/           trades (saga), holdings, market data, portfolio; own database
notification-service/      Kafka consumer with its own database
ui/                        web UI: React, TypeScript, Vite (Keycloak login, calls the APIs)
docker/                    Dockerfile, Keycloak realm, Postgres init script, Prometheus and Grafana configuration
ui/                        Web UI (React), its Dockerfile, nginx.conf and browser tests (e2e/)
k8s/                       Kubernetes manifests (Kustomize base and a kind overlay) and the kind cluster config
scripts/                   smoke test, kind-up and kind-down
docs/                      ADRs and roadmap
```
