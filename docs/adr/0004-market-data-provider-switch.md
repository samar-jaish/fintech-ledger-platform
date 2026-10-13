# ADR 0004: Market data behind a provider interface with a configuration switch

Status: Accepted

## Context

Trading and portfolio valuation need prices. Real vendors have rate limits and need API keys, and tests and demos must
run offline. A fake price that looks real is dangerous.

## Decision

`MarketDataProvider` has three implementations selected by `market.provider` (env `MARKET_PROVIDER`):
`simulated` (default, seeded and deterministic), `replay` (closes recorded in our own `price_history` table) and
`live` (Finnhub quotes, Twelve Data history). Every `Quote` carries a `source`, stored on each trade and shown on each
portfolio position. The live provider fails at startup if keys are missing and never includes URLs or keys in errors.
Prices are fetched before a database transaction starts, so no locks are held during a network call.

## Consequences

- Tests and demos need no network; "record once with live, then replay offline" gives real numbers repeatably.
- Free-tier limits change; verify them with the vendors before relying on live mode.
