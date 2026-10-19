#!/usr/bin/env bash
# End-to-end check of the containerised platform. Run after: docker compose --profile apps up -d --build
# It uses real HTTP calls against real containers: login, deposit, buy, replay, access rules and the Kafka event path.
# Needs: bash, curl, jq, docker. Used by the CI workflow; also runs locally in Git Bash or WSL.
set -euo pipefail

LEDGER=${LEDGER_URL:-http://localhost:8080}
TRADING=${TRADING_URL:-http://localhost:8082}
KEYCLOAK=${KEYCLOAK_URL:-http://localhost:8180}
COMPOSE="docker compose --profile apps"
RUN_ID=$RANDOM$RANDOM

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
for _ in $(seq 1 30); do
  # Read the logs into a variable first: grep -q closes the pipe early, which would make "set -o pipefail" report a failure.
  LOGS=$($COMPOSE logs notification-service 2>&1 || true)
  if grep -q "NOTIFY: Transfer" <<< "$LOGS"; then
    echo "notification received"
    echo; echo "SMOKE TEST PASSED"
    exit 0
  fi
  sleep 2
done
fail "no notification after 60 s"
