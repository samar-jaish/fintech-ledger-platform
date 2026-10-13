# Roadmap (free-only, Azure-flavoured)

| Phase | Build | Free tooling |
|---|---|---|
| 1 | Core ledger, schema, tests | Postgres in Docker, Testcontainers, Flyway |
| 1.5 | Market data (simulated / replay / live), trades, holdings, portfolio | Finnhub and Twelve Data free tiers (optional) |
| 2a | REST API with problem+json errors and idempotent writes (done) | Spring Web |
| 2b | Spring Security, OAuth2 JWT | Keycloak in Docker |
| 2c | Spring Batch nightly close | Spring Batch |
| 3 | 4 services, Kafka, outbox, saga, Redis, Resilience4j | Kafka (KRaft) + Redis in Docker; Eureka |
| 4 | Docker, K8s, CI/CD, Azure deploy, observability | kind/k3s locally; GitHub Actions; Azure Container Apps + Postgres Flexible Server (free allowances); GHCR; Prometheus/Grafana/Jaeger locally; Bicep or Terraform |
| 5 | Lucene+Tika, RAG, Presidio PII masking | pgvector, Ollama (local LLM), Presidio sidecar |

Azure rule: check the free-services page before creating anything, set a budget alert, and delete the
resource group after each demo.
