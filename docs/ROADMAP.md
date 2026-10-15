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
| 3b-2 | Trading service split out, trade saga with compensation | Kafka, service-to-service auth |
| 3c | Redis caching and Resilience4j (retry, circuit breaker) | Redis in Docker |
| 4 | Docker, K8s, CI/CD, Azure deploy, observability | kind/k3s locally; GitHub Actions; Azure Container Apps + Postgres Flexible Server (free allowances); GHCR; Prometheus/Grafana/Jaeger locally; Bicep or Terraform |
| 5 | Lucene+Tika, RAG, Presidio PII masking | pgvector, Ollama (local LLM), Presidio sidecar |

Azure rule: check the free-services page before creating anything, set a budget alert, and delete the
resource group after each demo.
