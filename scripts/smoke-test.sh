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
expect "wrong password is rejected" 401 "$(code -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -d '{"email":"testuser@test.com","password":"wrongpassword"}')"

echo "3. Gateway authentication"
expect "GET /api/v1/patients without token" 401 "$(code "$BASE_URL/api/v1/patients")"
expect "GET /api/v1/patients with token" 200 "$(code "$BASE_URL/api/v1/patients" -H "Authorization: Bearer $TOKEN")"

echo "4. Create patient (DB save -> gRPC billing -> Kafka event)"
BODY=$(printf '{"name":"Smoke Test","email":"%s","address":"1 Test Street","dateOfBirth":"1995-09-09","registeredDate":"2024-11-28"}' "$EMAIL")
expect "POST /api/v1/patients" 201 "$(code -X POST "$BASE_URL/api/v1/patients" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d "$BODY")"
expect "duplicate email is rejected" 409 "$(code -X POST "$BASE_URL/api/v1/patients" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d "$BODY")"

echo "4b. Day 2 API behaviour"
expect "login with too-short password fails validation" 400 "$(code -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -d '{"email":"testuser@test.com","password":"short"}')"
expect "unknown patient id returns 404" 404 "$(code "$BASE_URL/api/v1/patients/00000000-0000-0000-0000-000000000000" -H "Authorization: Bearer $TOKEN")"
expect "malformed id returns 400" 400 "$(code "$BASE_URL/api/v1/patients/not-a-uuid" -H "Authorization: Bearer $TOKEN")"
BAD=$(printf '{"name":"Bad Date","email":"bad_%s@example.com","address":"x","dateOfBirth":"31-12-1995","registeredDate":"2024-11-28"}' "$(date +%s)")
expect "malformed date returns 400 (not 500)" 400 "$(code -X POST "$BASE_URL/api/v1/patients" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d "$BAD")"
expect "delete unknown patient returns 404" 404 "$(code -X DELETE "$BASE_URL/api/v1/patients/00000000-0000-0000-0000-000000000000" -H "Authorization: Bearer $TOKEN")"

echo "4c. Day 4 pagination, sorting and search"
page_body=$(curl -s "$BASE_URL/api/v1/patients?size=2&sort=name,asc" -H "Authorization: Bearer $TOKEN")
if echo "$page_body" | grep -q '"totalElements"' && echo "$page_body" | grep -q '"content"'; then pass "list returns a paged envelope"; else fail "list did not return a paged envelope"; fi
expect "unknown sort field is rejected" 400 "$(code "$BASE_URL/api/v1/patients?sort=password,asc" -H "Authorization: Bearer $TOKEN")"
search_body=$(curl -s "$BASE_URL/api/v1/patients?search=$EMAIL" -H "Authorization: Bearer $TOKEN")
if echo "$search_body" | grep -q "\"totalElements\":1,"; then pass "search finds the patient just created"; else fail "search did not find the new patient"; fi

echo "4d. Day 7 idempotency (Idempotency-Key header)"
IDEM_KEY="smoke-key-$(date +%s)-$$"
IDEM_EMAIL="idem_$(date +%s)@example.com"
IDEM_BODY=$(printf '{"name":"Idem Test","email":"%s","address":"1 Test Street","dateOfBirth":"1995-09-09","registeredDate":"2024-11-28"}' "$IDEM_EMAIL")
idem_post() { curl -s -i -X POST "$BASE_URL/api/v1/patients" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $IDEM_KEY" -d "$1"; }
first=$(idem_post "$IDEM_BODY")
second=$(idem_post "$IDEM_BODY")
first_id=$(sed -n 's/.*"id":"\([^"]*\)".*/\1/p' <<<"$first" | head -1)
second_id=$(sed -n 's/.*"id":"\([^"]*\)".*/\1/p' <<<"$second" | head -1)
if grep -q "^HTTP/[0-9.]* 201" <<<"$first" && [ -n "$first_id" ]; then pass "first request with key creates the patient (201)"; else fail "first keyed request did not return 201 with an id"; fi
if grep -q "^HTTP/[0-9.]* 201" <<<"$second" && [ "$first_id" = "$second_id" ]; then pass "repeat with same key returns the same patient"; else fail "repeat did not return the same patient ($first_id vs $second_id)"; fi
if grep -qi "^idempotent-replayed: true" <<<"$second"; then pass "repeat is marked Idempotent-Replayed"; else fail "repeat was not marked as replayed"; fi
count_body=$(curl -s "$BASE_URL/api/v1/patients?search=$IDEM_EMAIL" -H "Authorization: Bearer $TOKEN")
if grep -q "\"totalElements\":1," <<<"$count_body"; then pass "exactly one patient exists for the key"; else fail "expected exactly one patient for $IDEM_EMAIL"; fi
DIFF_BODY=$(printf '{"name":"Different Name","email":"%s","address":"1 Test Street","dateOfBirth":"1995-09-09","registeredDate":"2024-11-28"}' "$IDEM_EMAIL")
expect "same key with a different body is rejected" 422 "$(code -X POST "$BASE_URL/api/v1/patients" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $IDEM_KEY" -d "$DIFF_BODY")"
expect "invalid Idempotency-Key is rejected" 400 "$(code -X POST "$BASE_URL/api/v1/patients" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: has space' -d "$IDEM_BODY")"

echo "5. Downstream effects (read from container logs)"
if command -v docker >/dev/null 2>&1 && docker compose ps >/dev/null 2>&1; then
  analytics_ok=0
  billing_ok=0
  for _ in $(seq 1 10); do
    # Capture the logs first, then grep. Piping `docker compose logs` straight into `grep -q`
    # breaks under `set -o pipefail`: grep exits at the first match, docker gets SIGPIPE, and the
    # whole pipeline reports failure even though the text was found.
    analytics_logs=$(docker compose logs --since 5m analytics-service 2>/dev/null || true)
    billing_logs=$(docker compose logs --since 5m billing-service 2>/dev/null || true)
    if grep -q "$EMAIL" <<<"$analytics_logs"; then analytics_ok=1; fi
    if grep -q "$EMAIL" <<<"$billing_logs"; then billing_ok=1; fi
    if [ "$analytics_ok" = "1" ] && [ "$billing_ok" = "1" ]; then break; fi
    sleep 2
  done
  if [ "$analytics_ok" = "1" ]; then pass "analytics-service consumed the Kafka event"; else fail "analytics-service did not log the event for $EMAIL"; fi
  if [ "$billing_ok" = "1" ]; then pass "billing-service received the gRPC call for this patient"; else fail "billing-service did not log a gRPC request for $EMAIL"; fi
else
  echo "  SKIP  docker compose not available here; check logs manually"
fi

echo
if [ "$FAILS" -eq 0 ]; then printf '\033[32mAll checks passed.\033[0m\n'; else printf '\033[31m%d check(s) failed.\033[0m\n' "$FAILS"; exit 1; fi