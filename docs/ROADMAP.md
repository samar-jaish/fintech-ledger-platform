# Roadmap (free-only, Azure-flavoured)

| Phase | Build | Free tooling |
|---|---|---|
| 1 | Core ledger, schema, tests | Postgres in Docker, Testcontainers, Flyway |
| 1.5 | Market data (simulated / replay / live), trades, holdings, portfolio | Finnhub and Twelve Data free tiers (optional) |
| 2a | REST API with problem+json errors and idempotent writes (done) | Spring Web |
| 2b | Spring Security, OAuth2 JWT, account ownership (done) | Keycloak in Docker |
| 2c | Spring Batch nightly close with end-of-day balances and reconciliation (done) | Spring Batch |
| 3a | Transactional outbox and Kafka events (done) | Kafka (KRaft) in Docker |
| 3b-1 | Maven modules; notification service with its own database and dead-letter topic (done) | Maven multi-module, Kafka |
| 3b-2 | Trading service split out, trade saga with compensation, service-to-service auth (done) | OAuth2 client credentials (Keycloak) |
| 3c | Resilience4j retry and circuit breaker around the ledger client; Redis quote cache (done) | Redis in Docker, Resilience4j |
| 4a | Dockerfile per service, one-command start of the whole platform with Docker Compose (done) | Docker, Docker Compose |
| 4b | CI: tests, an end-to-end smoke test of the containers, and image publishing (done) | GitHub Actions, GHCR |
| 4c | Health checks, metrics, dashboards, alerts and tracing (done) | Spring Boot Actuator, Micrometer, Prometheus, Grafana, OpenTelemetry, Jaeger |
| 4d | Kubernetes locally (done) | kind, kubectl, Kustomize |
| - | Checkpoint: close the loop on Phases 1-4 (per-phase theory and practical docs, run everything once, fix what breaks) | Docs, your own machine |
| 5a | UI foundation: React + Vite + TypeScript app, Keycloak login (OIDC authorization code with PKCE), CORS on the APIs (done) | React, Vite, keycloak-js or oidc-client-ts |
| 5b | UI features: accounts list and balances, add and send money with idempotency keys, statement, trading, portfolio and trade history (done). Live notifications need a notification API first and move to 5d | React Router, TanStack Query |
| 5c | UI delivery: nginx container, added to Compose and Kubernetes, published to GHCR, and real-browser tests in CI (done; component tests came with 5a and 5b) | nginx, Vitest, Playwright, GitHub Actions |
| 5d | Live notifications in the UI: a small notification API with its own security and CORS, pushed to the browser (server-sent events) | Spring Web, SSE |
| - | Checkpoint: one theory page per technology learned so far, plus a plain-words glossary of every command | Docs |
| 6 | Azure: Container Apps + Postgres Flexible Server with Terraform (check the free-services page, set a budget alert, delete the resource group after each demo) | Azure, Terraform |
| 7 | Lucene+Tika, RAG, Presidio PII masking | pgvector, Ollama (local LLM), Presidio sidecar |

Order matters: the UI comes before the cloud, and the cloud comes only after the checkpoint that makes sure
every technology so far is understood.

Azure rule: check the free-services page before creating anything, set a budget alert, and delete the
resource group after each demo.
