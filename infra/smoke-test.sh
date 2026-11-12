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
  binds=$(docker ps --filter "label=com.docker.compose.service=$1" --format '{{.Ports}}' | tr ',' '\n' | grep -- "->$2/" | sed 's/->.*//; s/^ *//')
  ok=1; [[ -z "$binds" ]] && ok=0
  while read -r bind; do [[ -z "$bind" || "$bind" == 127.0.0.1:* || "$bind" == "[::1]:"* ]] || ok=0; done <<< "$binds"
  [[ $ok == 1 ]] && pass "T-02-02 $1:$2 bound to loopback only ($(echo $binds | tr '\n' ' '))" || bad "T-02-02 $1:$2 bound to '${binds:-none}'"
done
# Windows resolves "localhost" to ::1 first: the app must answer there too
code6=$(curl -s -6 -o /dev/null -w '%{http_code}' --max-time 5 http://localhost:3000/ 2>/dev/null)
[[ "$code6" == "200" ]] && pass "T-02-06 http://localhost:3000 over IPv6 (::1) -> 200" || bad "T-02-06 localhost:3000 over IPv6 -> ${code6:-no answer}"
code6=$(curl -s -6 -o /dev/null -w '%{http_code}' --max-time 5 http://localhost:8080/api/v1/health 2>/dev/null)
[[ "$code6" == "200" ]] && pass "T-02-01 http://localhost:8080 over IPv6 (::1) -> 200" || bad "T-02-01 localhost:8080 over IPv6 -> ${code6:-no answer}"
# P11: SearXNG is internal only — never published, but the API reaches it and gets JSON
sx=$(docker compose port searxng 8080 2>/dev/null)
[[ "$sx" == *:[1-9]* ]] && bad "T-11 searxng 8080 is published at $sx" || pass "T-11 searxng not published"
sxjson=$(docker compose exec -T api sh -c 'curl -s -m 20 "http://searxng:8080/search?q=test&format=json"' 2>/dev/null)
[[ "$sxjson" == *'"results"'* ]] && pass "T-11 API reaches SearXNG on the internal network (JSON)" \
  || bad "T-11 API can't get JSON from SearXNG"

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
