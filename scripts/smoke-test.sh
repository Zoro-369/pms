#!/usr/bin/env bash
# End-to-end smoke test for the compose stack. Run after: docker compose up --build -d
# Usage: ./scripts/smoke-test.sh            (BASE_URL defaults to http://localhost:4004)
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:4004}"
EMAIL="smoke_$(date +%s)@example.com"
FAILS=0

pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILS=$((FAILS + 1)); }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
expect() { # expect <description> <expected_code> <actual_code>
  if [ "$3" = "$2" ]; then pass "$1 (HTTP $3)"; else fail "$1 (expected $2, got $3)"; fi
}

echo "PMS smoke test against $BASE_URL"

echo "1. Waiting for the gateway and auth service (up to 180s)..."
ready=0
for _ in $(seq 1 60); do
  c=$(code -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' \
        -d '{"email":"testuser@test.com","password":"password123"}')
  if [ "$c" = "200" ]; then ready=1; break; fi
  sleep 3
done
if [ "$ready" = "1" ]; then pass "login endpoint is up"; else fail "login endpoint not ready after 180s"; echo "Check: docker compose logs api-gateway auth-service"; exit 1; fi

echo "2. Login"
TOKEN=$(curl -s -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' \
          -d '{"email":"testuser@test.com","password":"password123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [ -n "$TOKEN" ]; then pass "received a JWT"; else fail "no token in login response"; exit 1; fi
expect "wrong password is rejected" 401 "$(code -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -d '{"email":"testuser@test.com","password":"wrong"}')"

echo "3. Gateway authentication"
expect "GET /api/patients without token" 401 "$(code "$BASE_URL/api/patients")"
expect "GET /api/patients with token" 200 "$(code "$BASE_URL/api/patients" -H "Authorization: Bearer $TOKEN")"

echo "4. Create patient (DB save -> gRPC billing -> Kafka event)"
BODY=$(printf '{"name":"Smoke Test","email":"%s","address":"1 Test Street","dateOfBirth":"1995-09-09","registeredDate":"2024-11-28"}' "$EMAIL")
expect "POST /api/patients" 200 "$(code -X POST "$BASE_URL/api/patients" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d "$BODY")"
expect "duplicate email is rejected" 400 "$(code -X POST "$BASE_URL/api/patients" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d "$BODY")"

echo "5. Downstream effects (read from container logs)"
if command -v docker >/dev/null 2>&1 && docker compose ps >/dev/null 2>&1; then
  found=0
  for _ in $(seq 1 10); do
    if docker compose logs --tail=200 analytics-service 2>/dev/null | grep -q "$EMAIL"; then found=1; break; fi
    sleep 2
  done
  if [ "$found" = "1" ]; then pass "analytics-service consumed the Kafka event"; else fail "analytics-service did not log the event for $EMAIL"; fi
  if docker compose logs --tail=200 billing-service 2>/dev/null | grep -q "createBillingAccount request received"; then
    pass "billing-service received the gRPC call"
  else
    fail "billing-service did not log a gRPC request"
  fi
else
  echo "  SKIP  docker compose not available here; check logs manually"
fi

echo
if [ "$FAILS" -eq 0 ]; then printf '\033[32mAll checks passed.\033[0m\n'; else printf '\033[31m%d check(s) failed.\033[0m\n' "$FAILS"; exit 1; fi