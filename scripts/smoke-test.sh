#!/usr/bin/env bash
# End-to-end check of the containerised platform. Run after: docker compose --profile apps up -d --build
# It uses real HTTP calls against real containers: login, deposit, buy, replay, access rules and the Kafka event path.
# Needs: bash, curl, jq, docker. Used by the CI workflow; also runs locally in Git Bash or WSL.
set -euo pipefail

LEDGER=${LEDGER_URL:-http://localhost:8080}
TRADING=${TRADING_URL:-http://localhost:8082}
KEYCLOAK=${KEYCLOAK_URL:-http://localhost:8180}
# Operations ports: health and metrics. Separate from the API ports on purpose.
LEDGER_OPS=${LEDGER_OPS_URL:-http://localhost:9080}
TRADING_OPS=${TRADING_OPS_URL:-http://localhost:9082}
NOTIFY_OPS=${NOTIFY_OPS_URL:-http://localhost:8083}
NOTIFY_API=${NOTIFY_API_URL:-http://localhost:8084}
PROMETHEUS=${PROMETHEUS_URL:-http://localhost:9090}
GRAFANA=${GRAFANA_URL:-http://localhost:3000}
JAEGER=${JAEGER_URL:-http://localhost:16686}
UI=${UI_URL:-http://localhost:8085}
# CHECK_OBSERVABILITY=true: Prometheus, Grafana and Jaeger are running too (--profile observability).
# CHECK_TRACING=true: only Jaeger is running (the Kubernetes setup).
COMPOSE="docker compose --profile apps"
RUN_ID=$RANDOM$RANDOM

# Where to read the notification service's log. Docker Compose by default; for Kubernetes set
# NOTIFICATION_LOGS_CMD="kubectl -n ledger logs deploy/notification-service".
notification_logs() {
  if [ -n "${NOTIFICATION_LOGS_CMD:-}" ]; then $NOTIFICATION_LOGS_CMD; else $COMPOSE logs notification-service; fi
}

step() { echo; echo "== $*"; }
fail() { echo "SMOKE TEST FAILED: $*" >&2; exit 1; }

# Poll a URL until it answers with the expected HTTP status (any answer proves the service is up and secured).
wait_for_status() {
  local name=$1 url=$2 expected=$3 code=""
  for _ in $(seq 1 90); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$url" || true)
    [ "$code" = "$expected" ] && { echo "$name is up (HTTP $code)"; return 0; }
    sleep 3
  done
  fail "$name did not answer HTTP $expected within 270 s (last answer: ${code:-none})"
}

step "Wait for the services"
wait_for_status "keycloak" "$KEYCLOAK/realms/ledger/.well-known/openid-configuration" 200
wait_for_status "ledger-service" "$LEDGER/api/v1/accounts/00000000-0000-0000-0000-000000000000" 401
wait_for_status "trading-service" "$TRADING/api/v1/accounts/00000000-0000-0000-0000-000000000000/portfolio" 401

step "The web UI is served, and a deep link falls back to the app (single-page routing)"
wait_for_status "web ui" "$UI/healthz" 200
curl -fsS "$UI/" | grep -q '<div id="root">' || fail "the UI home page does not contain the app root"
curl -fsS "$UI/accounts/some-id/trading" | grep -q '<div id="root">' || fail "a deep link did not fall back to index.html"

step "Log in as alice"
TOKEN_URL="$KEYCLOAK/realms/ledger/protocol/openid-connect/token"
TOKEN=$(curl -fsS -d grant_type=password -d client_id=ledger-api -d username=alice -d password=alice123 "$TOKEN_URL" | jq -r .access_token)
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || fail "no access token"
AUTH="Authorization: Bearer $TOKEN"

step "Open an account and deposit 1000"
ACCOUNT=$(curl -fsS -X POST "$LEDGER/api/v1/accounts" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"ownerName":"Alice","currency":"USD"}' | jq -r .id)
[ -n "$ACCOUNT" ] && [ "$ACCOUNT" != "null" ] || fail "account was not created"
curl -fsS -X POST "$LEDGER/api/v1/accounts/$ACCOUNT/deposits" -H "$AUTH" -H "Idempotency-Key: smoke-dep-$RUN_ID" \
  -H 'Content-Type: application/json' -d '{"amount":1000}' > /dev/null
BALANCE=$(curl -fsS "$LEDGER/api/v1/accounts/$ACCOUNT" -H "$AUTH" | jq -r .balance)
echo "balance: $BALANCE"
[ "$(echo "$BALANCE" | jq 'tonumber == 1000')" = "true" ] || fail "expected balance 1000, got $BALANCE"

step "Buy 5 AAPL (trading calls the ledger with its own service token)"
TRADE_KEY="smoke-trade-$RUN_ID"
TRADE_BODY=$(jq -n --arg a "$ACCOUNT" '{accountId:$a,symbol:"AAPL",side:"BUY",quantity:5}')
TRADE=$(curl -fsS -X POST "$TRADING/api/v1/trades" -H "$AUTH" -H "Idempotency-Key: $TRADE_KEY" -H 'Content-Type: application/json' -d "$TRADE_BODY")
echo "$TRADE" | jq '{id,status,price,notional}'
[ "$(echo "$TRADE" | jq -r .status)" = "COMPLETED" ] || fail "trade is not COMPLETED"

step "Replay the same request: same trade, no second charge"
REPLAY=$(curl -fsS -X POST "$TRADING/api/v1/trades" -H "$AUTH" -H "Idempotency-Key: $TRADE_KEY" -H 'Content-Type: application/json' -d "$TRADE_BODY")
[ "$(echo "$REPLAY" | jq -r .id)" = "$(echo "$TRADE" | jq -r .id)" ] || fail "replay returned a different trade"
[ "$(echo "$REPLAY" | jq -r .replayed)" = "true" ] || fail "replay was not marked as replayed"

step "Portfolio: cash went down, 5 shares held"
PORTFOLIO=$(curl -fsS "$TRADING/api/v1/accounts/$ACCOUNT/portfolio" -H "$AUTH")
echo "$PORTFOLIO" | jq '{cash,positions:[.positions[]|{symbol,quantity}],pendingTrades}'
[ "$(echo "$PORTFOLIO" | jq '(.cash | tonumber) < 1000')" = "true" ] || fail "cash did not go down"
[ "$(echo "$PORTFOLIO" | jq '[.positions[] | select(.symbol=="AAPL")] | length')" = "1" ] || fail "no AAPL position"

step "Too expensive: rejected with INSUFFICIENT_FUNDS"
BIG_BODY=$(jq -n --arg a "$ACCOUNT" '{accountId:$a,symbol:"AAPL",side:"BUY",quantity:100000}')
CODE=$(curl -s -o /tmp/smoke-big.json -w '%{http_code}' -X POST "$TRADING/api/v1/trades" -H "$AUTH" \
  -H "Idempotency-Key: smoke-big-$RUN_ID" -H 'Content-Type: application/json' -d "$BIG_BODY")
[ "$CODE" = "422" ] || fail "expected HTTP 422, got $CODE"
[ "$(jq -r .code /tmp/smoke-big.json)" = "INSUFFICIENT_FUNDS" ] || fail "expected code INSUFFICIENT_FUNDS"

step "Someone else's account is invisible (bob gets 404)"
BOB=$(curl -fsS -d grant_type=password -d client_id=ledger-api -d username=bob -d password=bob123 "$TOKEN_URL" | jq -r .access_token)
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$TRADING/api/v1/accounts/$ACCOUNT/portfolio" -H "Authorization: Bearer $BOB")
[ "$CODE" = "404" ] || fail "expected HTTP 404 for bob, got $CODE"

step "Internal endpoint: a user token is forbidden, a service token is allowed"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$LEDGER/api/v1/internal/exchange-accounts" -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"currency":"USD"}')
[ "$CODE" = "403" ] || fail "expected HTTP 403 for a user token, got $CODE"
SERVICE=$(curl -fsS -d grant_type=client_credentials -d client_id=trading-service -d client_secret=trading-service-secret "$TOKEN_URL" | jq -r .access_token)
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$LEDGER/api/v1/internal/exchange-accounts" -H "Authorization: Bearer $SERVICE" \
  -H 'Content-Type: application/json' -d '{"currency":"USD"}')
[ "$CODE" = "200" ] || [ "$CODE" = "201" ] || fail "expected HTTP 200/201 for a service token, got $CODE"

step "The transfer event reached the notification service through Kafka"
NOTIFIED=false
for _ in $(seq 1 30); do
  # Read the logs into a variable first: grep -q closes the pipe early, which would make "set -o pipefail" report a failure.
  LOGS=$(notification_logs 2>&1 || true)
  if grep -q "NOTIFY: Transfer" <<< "$LOGS"; then
    NOTIFIED=true
    break
  fi
  sleep 2
done
[ "$NOTIFIED" = "true" ] || fail "no notification after 60 s"
echo "notification received"

step "The notification API: alice sees her own notifications, bob cannot see hers, no token is refused, the live stream opens"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$NOTIFY_API/api/v1/notifications")
[ "$CODE" = "401" ] || fail "expected HTTP 401 without a token, got $CODE"
ALICE_COUNT=0
for _ in $(seq 1 15); do
  ALICE_COUNT=$(curl -fsS "$NOTIFY_API/api/v1/notifications" -H "$AUTH" | jq 'length')
  [ "$ALICE_COUNT" -ge 1 ] 2>/dev/null && break
  sleep 2
done
[ "$ALICE_COUNT" -ge 1 ] 2>/dev/null || fail "alice has no notifications for her own transfers"
# Bob may have notifications of his own (from earlier runs), but none may concern alice's account.
BOB_SEES_ALICE=$(curl -fsS "$NOTIFY_API/api/v1/notifications?limit=100" -H "Authorization: Bearer $BOB" \
  | jq --arg a "$ACCOUNT" '[.[] | select(.fromAccountId == $a or .toAccountId == $a)] | length')
[ "$BOB_SEES_ALICE" = "0" ] || fail "bob can see $BOB_SEES_ALICE notification(s) about alice's account"
# The stream never ends by itself, so curl is stopped after 3 seconds (exit code 28 is expected) and only the headers are read.
STREAM_OUT=$(mktemp)
curl -sS -N --max-time 5 -D "$STREAM_OUT" -o /dev/null -H "$AUTH" -H 'Accept: text/event-stream' "$NOTIFY_API/api/v1/notifications/stream" || true
STREAM_HEADERS=$(cat "$STREAM_OUT")
if ! grep -qi '^content-type: *text/event-stream' <<< "$STREAM_HEADERS"; then
  echo "--- what the stream endpoint answered (empty means no headers arrived within 5 seconds):"
  echo "$STREAM_HEADERS"
  fail "the live stream did not answer with text/event-stream"
fi
PREFLIGHT=$(curl -sS -D - -o /dev/null -X OPTIONS "$NOTIFY_API/api/v1/notifications/stream" -H 'Origin: http://localhost:8085' \
  -H 'Access-Control-Request-Method: GET' -H 'Access-Control-Request-Headers: authorization,last-event-id')
grep -qi '^access-control-allow-origin: http://localhost:8085' <<< "$PREFLIGHT" || fail "the notification API does not allow the web UI origin (CORS)"
echo "notification API ok"

step "Health probes: all three services are live and ready"
wait_for_status "ledger-service readiness" "$LEDGER_OPS/actuator/health/readiness" 200
wait_for_status "trading-service readiness" "$TRADING_OPS/actuator/health/readiness" 200
wait_for_status "notification-service readiness" "$NOTIFY_OPS/actuator/health/readiness" 200
for ops in "$LEDGER_OPS" "$TRADING_OPS" "$NOTIFY_OPS"; do
  LIVE=$(curl -fsS "$ops/actuator/health/liveness" | jq -r .status || true)
  [ "$LIVE" = "UP" ] || fail "liveness at $ops is '$LIVE', expected UP"
done

step "Metrics: Prometheus format on the operations ports, with our business numbers in it"
TRADING_METRICS=$(curl -fsS "$TRADING_OPS/actuator/prometheus")
LEDGER_METRICS=$(curl -fsS "$LEDGER_OPS/actuator/prometheus")
NOTIFY_METRICS=$(curl -fsS "$NOTIFY_OPS/actuator/prometheus")
grep -Eq '^http_server_requests_seconds_count\{' <<< "$TRADING_METRICS" || fail "no HTTP request metrics from trading-service"
grep -Eq 'trading_trades_total\{[^}]*status="COMPLETED"[^}]*\} [1-9]' <<< "$TRADING_METRICS" || fail "trading_trades_total does not show the completed trade"
grep -Eq '^trading_trades_pending\{' <<< "$TRADING_METRICS" || fail "trading_trades_pending is missing"
grep -Eq '^ledger_client_circuit_state\{[^}]*\} 0' <<< "$TRADING_METRICS" || fail "the ledger circuit breaker should be CLOSED (0)"
grep -Eq '^ledger_outbox_pending\{' <<< "$LEDGER_METRICS" || fail "ledger_outbox_pending is missing"
grep -Eq 'notification_events_total\{[^}]*result="stored"[^}]*\} [1-9]' <<< "$NOTIFY_METRICS" || fail "notification_events_total does not show the stored notification"
echo "business metrics are present"

step "The operations endpoints are not served on the public API ports"
for api in "$LEDGER" "$TRADING"; do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' "$api/actuator/prometheus")
  [ "$CODE" != "200" ] || fail "metrics must not be served on the API port ($api)"
done

if [ "${CHECK_OBSERVABILITY:-false}" = "true" ]; then
  step "Prometheus is scraping all three services"
  UP=0
  for _ in $(seq 1 30); do
    UP=$(curl -fsS "$PROMETHEUS/api/v1/targets" 2>/dev/null | jq '[.data.activeTargets[] | select(.health=="up")] | length' 2>/dev/null || echo 0)
    [ "$UP" = "3" ] && break
    sleep 3
  done
  [ "$UP" = "3" ] || fail "Prometheus sees $UP healthy targets, expected 3"
  RULES=$(curl -fsS "$PROMETHEUS/api/v1/rules" | jq '[.data.groups[].rules[]] | length')
  [ "$RULES" = "6" ] || fail "expected 6 alert rules to be loaded, found $RULES"

  step "Grafana has the provisioned dashboard"
  FOUND=0
  for _ in $(seq 1 20); do
    FOUND=$(curl -fsS "$GRAFANA/api/search?query=Ledger%20platform" 2>/dev/null | jq 'length' 2>/dev/null || echo 0)
    [ "$FOUND" -ge 1 ] 2>/dev/null && break
    sleep 3
  done
  [ "$FOUND" -ge 1 ] 2>/dev/null || fail "the 'Ledger platform' dashboard was not found in Grafana"
fi

if [ "${CHECK_OBSERVABILITY:-false}" = "true" ] || [ "${CHECK_TRACING:-false}" = "true" ]; then
  step "Jaeger shows one trace that crosses trading-service and ledger-service"
  CROSS=0
  for _ in $(seq 1 30); do
    CROSS=$(curl -fsS "$JAEGER/api/traces?service=trading-service&limit=50" 2>/dev/null \
      | jq '[.data[] | select([.processes[].serviceName] | unique | contains(["ledger-service","trading-service"]))] | length' 2>/dev/null || echo 0)
    [ "$CROSS" -ge 1 ] 2>/dev/null && break
    sleep 3
  done
  [ "$CROSS" -ge 1 ] 2>/dev/null || fail "no trace spans both services: trace context is not being passed between them"
fi

echo; echo "SMOKE TEST PASSED"
