# ADR 0013: Container images and a Compose profile for the applications

Status: Accepted

## Context

Until now only the infrastructure (Postgres, Keycloak, Kafka, Redis) ran in Docker, and each service was started from
Maven. To deploy anywhere (CI, Kubernetes, Azure) every service needs a container image, and a new developer should be
able to start the whole platform with one command.

## Decision

- One `docker/Dockerfile` for all services, selected with the build argument `MODULE`. Fewer copies means fewer
  places to drift apart.
- Multi-stage build: a Maven stage compiles and packages the module, and the final image contains only a JRE and the
  jar. It carries no compiler, no Maven and no sources, so it is smaller and exposes less.
- The container runs as a non-root user, and the JVM sizes its heap from the container's memory limit
  (`-XX:MaxRAMPercentage=70`) rather than from the host's memory.
- Dependencies are cached between builds with a BuildKit cache mount, so only the first build downloads everything.
- The applications are in a Compose profile named `apps`. `docker compose up -d` still starts only the infrastructure,
  which the Maven tests and local development use. `docker compose --profile apps up -d --build` starts everything.
- Configuration comes from environment variables, not from files baked into the image: database URLs, the Kafka
  address, the ledger address and feature switches. The same image can then run anywhere.
- Kafka has two listeners. `localhost:9092` is for programs on the host, and `kafka:19092` is for other containers.
  A broker tells clients the address to reconnect to, so the address a container needs differs from the one the host needs.
- Token issuer. A token's `iss` claim is the address Keycloak was asked at. Keycloak is fixed to
  `KC_HOSTNAME=http://localhost:8180`, so every token says `localhost:8180` whether it was requested from the host or
  from another container. The services then validate the issuer as `http://localhost:8180/realms/ledger` but fetch the
  signing keys from `http://keycloak:8180/...` (`jwk-set-uri`), which is the address that works on the container network.

## Consequences

- Images are built from source inside Docker, so a build needs access to Maven Central. Behind a corporate proxy the
  build stage needs proxy settings or a mirror. CI (next step) builds the same way.
- Three Spring Boot services, Keycloak, Kafka, Postgres and Redis need several gigabytes of memory. Each service is capped at 768 MB.
- There are no container health checks for the applications yet. `depends_on` waits for Postgres to be healthy but only
  for the others to have started. Actuator health endpoints and proper checks arrive with observability.
- Secrets such as the demo database password and the Keycloak client secret are in the Compose file for local use only.
  A real deployment injects them from the platform's secret store.
