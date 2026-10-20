# ADR 0015: Health checks, metrics, dashboards, alerts and tracing

Status: Accepted

## Context

With three services, Kafka and a saga, "is it working?" can no longer be answered by looking at one log. Failures here are
often silent: a trade stays PENDING, events pile up in the outbox, a message lands in the dead-letter topic, or a circuit
breaker opens. Each is invisible to a user's HTTP response yet matters to the business. Operations also needs a signal to
decide whether a container should receive traffic or be restarted.

## Decision

- **Health.** Spring Boot Actuator exposes `/actuator/health/liveness` and `/actuator/health/readiness`. Liveness means the
  process is alive and never depends on other systems. Readiness means the service can do its job and includes the database.
  Redis counts only when the quote cache is enabled, because the cache is optional (it fails open). Docker Compose health
  checks use the readiness endpoint, and `depends_on` waits for `service_healthy`, replacing "started" with "ready".
- **Separate management port.** Health and metrics are served on their own port (ledger 9080, trading 9082, notification
  8083 where nothing else is served). The API port has no such paths. In Compose the operations ports are published to
  `127.0.0.1` only, and Prometheus reaches them over the Compose network. Only `health`, `info` and `prometheus` are exposed.
- **Metrics.** Micrometer with the Prometheus registry. Every metric carries an `application` tag. HTTP latency histograms
  are enabled so p95 can be computed. Business metrics: trades by final status, pending trades and the age of the oldest,
  outbox events waiting and the age of the oldest, circuit breaker state, failure rate, refused calls and retries,
  notifications stored versus ignored as duplicates, and dead-lettered messages.
- **Counts read from the database.** Trade and outbox numbers are computed from the tables when Prometheus scrapes, so
  they are correct after a restart and need no code in the saga or the relay. The queries use existing indexes.
- **Dashboards and alerts as files.** Prometheus, Grafana data sources and the dashboard are provisioned from files in
  `docker/`, so the setup is reproducible and reviewed like code. Six alert rules describe symptoms: service down, 5xx
  rate, breaker open, trade pending for over a minute, outbox backlog, dead letter.
- **Tracing.** Micrometer Tracing with the OpenTelemetry bridge, exported over OTLP to Jaeger. HTTP calls between services
  and Kafka messages carry the trace context, and log lines include the trace and span id. Sampling is 100% in Compose
  and 0% by default elsewhere (ids still appear in logs; nothing is exported).
- **Checked in CI.** The smoke test now verifies health, the presence of the business metrics, that the API port does not
  serve metrics, that Prometheus scrapes all three services and has loaded the rules, that Grafana has the dashboard, and
  that Jaeger holds a trace spanning trading-service and ledger-service.

## Consequences

- The operations port has no authentication. That is acceptable only because it is not reachable from outside: in
  Kubernetes or Azure it must stay off the public ingress, and a network policy should restrict who can scrape it.
- The trace stops at the outbox. The relay publishes from its own thread, so the Kafka message starts a new trace instead
  of continuing the one from the HTTP request. Linking them needs the trace context stored in the outbox row.
- Percentile histograms increase the number of time series. That is fine at this size; at scale, limit them to chosen
  endpoints.
- Alerts only appear in the Prometheus UI. Delivering them needs Alertmanager and a destination such as email or chat.
- Sampling every request is for demos. Production services sample a fraction, or use tail-based sampling in a collector
  to keep slow and failed requests.
- The metrics queries run against the primary database on every scrape. Several instances or very frequent scrapes
  would justify caching them for a few seconds.
- Image versions of Prometheus, Grafana and Jaeger are pinned in `docker-compose.yml`; Dependabot does not watch Compose files.
