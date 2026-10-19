# ADR 0014: CI with an end-to-end smoke test and image publishing

Status: Accepted

## Context

Unit and integration tests prove each service on its own. They do not prove that the packaged images start, find each
other by name, accept each other's tokens and pass events through Kafka. Those wiring problems (a wrong address, a token
issuer mismatch) are exactly what breaks after a refactor, and they only show up when everything runs together.
Images also need a home before any deployment can pull them.

## Decision

- The pipeline has three jobs in order: `test`, `smoke`, `images`. A later job runs only if the earlier one passed.
- `test` runs `mvn verify` (unit tests and Testcontainers integration tests). Test reports are kept as a build
  artifact when it fails.
- `smoke` builds the images, starts the platform with `docker compose --profile apps`, and runs `scripts/smoke-test.sh`.
  The script waits for the services, logs in through Keycloak, deposits, buys, replays the same request, checks the
  portfolio, checks a rejected trade, an unauthorized read, the internal endpoint's role check, and that the transfer
  event arrived at the notification service. On failure the job prints the container logs.
- `images` runs only for pushes to `master`. It builds each service image with the same Dockerfile and pushes it to
  `ghcr.io`, tagged `latest` and with the short commit id. A cache stored in GitHub Actions keeps later builds fast.
- Authentication to the registry uses the workflow's own `GITHUB_TOKEN` with `packages: write` on that one job. No
  personal access token or secret is stored. Everything else runs with read-only permissions.
- A newer push to the same branch cancels the older run.
- Dependabot opens weekly pull requests for Maven, GitHub Actions and Docker base image updates.

## Consequences

- The smoke test runs real containers, so it is slower than unit tests (several minutes) and can be affected by runner
  resources and timing. The script polls with generous time limits instead of fixed sleeps.
- Images are built twice on master: once for the smoke test through Compose and once for publishing. Publishing from the
  exact images that passed the smoke test would remove the duplication but needs a registry round trip between jobs.
- The first published package is private until its visibility is changed in the package settings on GitHub.
- Images carry the commit id as a tag, so a deployment can name an exact version. `latest` moves with every push and
  should not be used for real deployments.
- There is no vulnerability scan or image signing yet; both fit naturally as further steps in this pipeline.
