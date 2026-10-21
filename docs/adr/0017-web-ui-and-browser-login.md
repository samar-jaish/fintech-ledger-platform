# ADR 0017: Web UI with React, TypeScript and browser login

Status: Accepted (phase 5a)

## Context

The platform has only REST APIs. A user interface shows the system working end to end and exercises the parts that only
matter once a browser is involved: login without handing a password to the application, cross-origin calls, and showing
the API's errors to a person.

## Decision

- **React with TypeScript, built by Vite.** A single-page app in `ui/`. Vite gives a fast dev server and a small static
  build; TypeScript catches mismatches between what the screens expect and what the API returns.
- **Login with the OIDC authorization code flow and PKCE**, through the `oidc-client-ts` library and a new public client,
  `ledger-ui`, in the Keycloak realm. The browser is sent to Keycloak's login page, so the UI never sees a password. A public
  client has no secret (a browser cannot hide one); PKCE protects the one-time code instead: the app creates a secret
  verifier, sends only its hash at the start, and must present the verifier to exchange the code. Implicit flow and
  password grant are switched off for this client.
- **Tokens live in sessionStorage.** They survive a refresh and vanish when the tab closes. Keeping them only in memory is
  safer against script injection but logs the user out on every refresh; for an app that renders no user-supplied HTML and
  loads no third-party scripts, sessionStorage is the accepted trade-off. Silent renewal uses the refresh token, not a
  hidden iframe.
- **The UI only reads the token to show who is signed in.** Every decision (who may see which account, who is an admin)
  is made again by the APIs from the same token. Hiding a button is a convenience, never security.
- **CORS on the two public APIs.** The UI is served from another origin, so the browser asks permission first (a
  preflight request). `ledger-service` and `trading-service` answer it for the origins in `app.cors.allowed-origins`
  (default: the dev server on port 5173; compose and Kubernetes add the containerised UI on port 8085), allow only GET,
  POST and the three headers the UI sends (`Authorization`, `Content-Type`, `Idempotency-Key`), and do not enable cookies
  because none are used. Error responses carry the CORS headers too; without them the browser shows a 401 as a network
  failure and hides the reason.
- **Configuration at build time.** API and Keycloak addresses come from `VITE_*` variables with defaults that match the
  compose and kind setups.
- **No UI framework yet.** Plain CSS with light and dark themes. A component library is a later decision, taken only if
  the screens need it.

## Consequences

- The UI is tested without a browser login or network: components against a fake session and fake API (Vitest and
  Testing Library), the HTTP wrapper against a fake `fetch`. The real login is covered by the end-to-end browser test in
  phase 5c.
- Keycloak reads the realm file only on first start. After this change an existing Keycloak must be recreated
  (`docker compose up -d --force-recreate keycloak`; the kind script restarts it) or the `ledger-ui` client will not exist.
- Allowed origins are configuration, not code: a deployed UI adds its own address to `APP_CORS_ALLOWED_ORIGINS` and to the
  client's redirect URIs.
