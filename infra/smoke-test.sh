#!/usr/bin/env bash
# P02 smoke test for the running local stack. Run from anywhere: bash infra/smoke-test.sh
set -uo pipefail
cd "$(dirname "$0")"

fail=0
pass() { echo "PASS  $1"; }
bad()  { echo "FAIL  $1"; [[ -n "${GITHUB_ACTIONS:-}" ]] && echo "::error::$1"; fail=1; }

# T-02-01 public health
body=$(curl -s -o - -w '\n%{http_code}' http://127.0.0.1:8080/api/v1/health)
code=${body##*$'\n'}; json=${body%$'\n'*}
[[ "$code" == "200" && "$json" == '{"status":"UP"}' ]] \
  && pass "T-02-01 GET /api/v1/health -> 200 $json" \
  || bad  "T-02-01 GET /api/v1/health -> $code $json"

# T-02-02 nothing exposed beyond localhost; actuator not published at all
mgmt=$(docker compose port api 8081 2>/dev/null)
[[ "$mgmt" == *:[1-9]* ]] && bad "T-02-02 actuator 8081 is published at $mgmt" || pass "T-02-02 actuator 8081 not published"
for svc_port in "postgres 5432" "api 8080" "web 3000"; do
  set -- $svc_port
  bind=$(docker compose port "$1" "$2" 2>/dev/null)
  [[ "$bind" == 127.0.0.1:* ]] && pass "T-02-02 $1:$2 bound to $bind only" || bad "T-02-02 $1:$2 bound to '${bind:-none}'"
done
resp=$(curl -s -w '\n%{http_code}' http://127.0.0.1:8080/actuator/health)
code=${resp##*$'\n'}
[[ ( "$code" == "401" || "$code" == "404" ) && "$resp" != *'"UP"'* ]] \
  && pass "T-02-02 /actuator not served on app port ($code)" \
  || bad  "T-02-02 /actuator on app port -> $code"

# T-05-05 /me without a session -> 401 Problem Details (no HTML login page)
resp=$(curl -s -D - -o /dev/null http://127.0.0.1:8080/api/v1/me)
[[ "$resp" == *" 401"* && "$resp" == *"application/problem+json"* ]] \
  && pass "T-05-05 GET /api/v1/me without cookie -> 401 problem+json" \
  || bad  "T-05-05 /me without cookie"

# T-07 BYOK endpoints are private
code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/v1/connections)
[[ "$code" == "401" ]] && pass "T-07 GET /api/v1/connections without login -> 401" || bad "T-07 /connections without login -> $code"

# T-02-06 web placeholder
page=$(curl -s -w '\n%{http_code}' http://127.0.0.1:3000/)
code=${page##*$'\n'}
[[ "$code" == "200" && "$page" == *"Anveshan"* ]] \
  && pass "T-02-06 http://localhost:3000 -> 200, page shows 'Anveshan'" \
  || bad  "T-02-06 http://localhost:3000 -> $code"

echo
[[ $fail == 0 ]] && echo "ALL SMOKE CHECKS PASSED" || echo "SOME CHECKS FAILED"
exit $fail
