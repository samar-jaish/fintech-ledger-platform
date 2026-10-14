# ADR 0007: OAuth2 resource server with Keycloak, and per-account ownership

Status: Accepted

## Context

The API moves money, so every call needs a known caller, and a caller must not reach other people's accounts.

## Decision

- The service does not handle passwords. Keycloak issues signed JWTs; this service only validates them (signature,
  issuer, expiry) as an OAuth2 resource server. Keys are discovered from the issuer on the first request.
- Stateless: no sessions, no cookies, hence no CSRF protection needed.
- Authentication tells us who the caller is (`sub`); roles come from the realm roles claim (`USER`, `ADMIN`).
- Authorization has two layers. URL rules in the security filter chain handle roles (reconciliation and `/api/v1/admin/**` are ADMIN only,
  everything else needs a token, any other path is denied). Ownership is a separate check, `AccountAccess`, because it
  depends on data: `accounts.owner_subject` stores the owner's `sub`.
- Someone else's account returns 404, not 403, so account ids cannot be probed.
- Transfers must start from an account the caller owns; the destination may be any account.
- The 401 and 403 bodies are fixed problem+json and echo nothing from the request.
- Local Keycloak uses `start-dev`, an imported demo realm and a password-grant client for convenience. That is
  not a production setup.

## Consequences

- Revoking a user or changing roles takes effect when their current token expires.
- Ownership is one account to one owner; shared or joint accounts would need an ownership table.
- Deposits are still a simulated cash-in available to account owners.
