# ADR 0019: The UI as a container, and browser tests

Status: accepted

## Context

Until now the UI ran only as a Vite development server on port 5173. A real deployment needs the UI as a built artifact that
is served the same way everywhere, and the platform needs a test that proves the pieces work together in a browser, which
unit tests with fake APIs cannot.

## Decision

- **The UI is its own deployable: a static site in an nginx container.** It is not served by one of the Java services. The
  UI changes at a different pace and has no server logic; a static image is small, fast to start and cannot leak secrets it
  does not have. A multi-stage `ui/Dockerfile` compiles the app with Node, then copies only the compiled files into
  `nginxinc/nginx-unprivileged` (runs as a normal user, listens on 8080). The final image has no Node and no source.
- **Address settings are build-time.** A browser cannot read server environment variables, so the Keycloak and API addresses
  are compiled in (`--build-arg`). The defaults match Compose and kind. A production image is built per environment, or the
  settings move to a small `config.json` loaded at start (a candidate for phase 6).
- **nginx details that matter.** Unknown paths fall back to `index.html` (the browser router owns `/accounts/...`);
  `index.html` is never cached while hashed files under `/assets/` are cached for a year; security headers include a
  Content-Security-Policy that allows calls only to the two APIs and Keycloak. A `/healthz` route serves probes.
- **Port 8085 everywhere.** Compose publishes 8085 to the container's 8080; kind maps NodePort 30085 to host 8085. Keycloak's
  `ledger-ui` client and the APIs' CORS settings already allowed `http://localhost:8085`.
- **Kubernetes:** a Deployment with two probes, a read-only root filesystem (writable `/tmp` only), all capabilities
  dropped, non-root, and small resource limits, plus a Service; the kind overlay exposes it as a NodePort.
- **Browser tests with Playwright, run in CI after the smoke test.** One journey uses a real Chromium: sign in at Keycloak,
  open two accounts, add money, send money, buy a share, and see each result on screen; a second test checks that a signed-out
  visitor sees only the sign-in page. They run against the Compose stack, so they cover the served UI, the login redirect,
  CORS, both APIs and the ledger together. On failure the report and traces are uploaded as a build artifact.
- **The smoke test also checks the UI** (health route, home page and a deep link), so the Kubernetes job covers the UI image too.
- **The UI image is published to GHCR** next to the three services, with the same tags (commit id and `latest`).

## Consequences

- Tests are layered: unit and component tests (fast, fake API, every change), browser tests (slow, real stack, once per push).
  Vitest is limited to `src/` so it never tries to run the Playwright files.
- The Content-Security-Policy names `localhost` addresses. An image built for other addresses needs a matching policy.
- Browser tests create accounts for `alice` on every run; the CI database is thrown away afterwards, a local one keeps them.
- The browser tests cannot run in a sandbox without the platform, so they were written against the real screens and checked in
  CI, not before.
