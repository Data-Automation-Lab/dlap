#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# ---------------------------------------------------------------------------
# Quickstart Keycloak smoke (IAM-02).
#
# Starts the keycloak service of the quickstart compose pair (deployment/environments/dev), with
# throwaway secrets, and checks what the harnesses and the UI rely on after the move to Keycloak 26:
#
#   D01  the server is Keycloak 26.8.1
#   D02  the scripts feature is off
#   D03  the container is healthy through keycloak-health-check.sh (management port 9000)
#   D04  the dev seed is kept: admin, cdo, director, dataengineer and datascientist exist
#   D05  no identity provider (the Google and Facebook brokers are gone)
#   D06  the harness path: password grant on dlap-backend as admin; the token carries sub, ROLE_ADMIN
#        and the audience dlap-api
#   D07  service token of dlap-backend: ROLE_SERVICE_ACCOUNT, sub, audience dlap-api
#   D08  dlap-mcp-gateway token: audience dlap-mcp-pep only
#   D09  dlap-ui refuses the password grant
#   D10  browser sign-in on dlap-ui (authorization code with PKCE) as admin: no OTP, ROLE_ADMIN
#   D11  the secrets chosen in the environment reached the realm import (a wrong secret is refused)
#   D12  a client added later through the admin API gets the realm default scopes, so its tokens carry sub
#
# Runs under the host-wide harness lock (HARN-01) in its own compose project and removes it at the end
# unless --keep is given. Needs Docker with Compose, curl and python3 (or python).
#
# Usage: deployment/platform/keycloak/tests/dev-realm-smoke.sh [--keep]
# Exit status: 0 when every check passes, 1 when one fails, 2 on a usage or setup problem.
# ---------------------------------------------------------------------------
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../../.." && pwd)"
DEV="$ROOT/deployment/environments/dev"
LOGIN_PY="$HERE/oidc_login.py"

KEEP=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep) KEEP=1; shift ;;
    -h|--help) sed -n '/^# Quickstart Keycloak smoke/,/^# -----/p' "$0" | sed 's/^# \{0,1\}//' | sed '$d'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

say()  { echo "[devsmoke] $*"; }
PASS=0; FAIL=0; FAILED=()
ok()   { PASS=$((PASS + 1)); echo "  PASS $1 $2"; }
bad()  { FAIL=$((FAIL + 1)); FAILED+=("$1"); echo "  FAIL $1 $2${3:+ ($3)}"; }
check() { # check <id> <description> <expected> <actual>
  if [[ "$3" == "$4" ]]; then ok "$1" "$2"; else bad "$1" "$2" "expected '$3', got '$4'"; fi
}

PY="$(command -v python3 || command -v python || true)"
[[ -n "$PY" ]] || { echo "python3 is required" >&2; exit 2; }
command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 2; }
docker compose version >/dev/null 2>&1 || { echo "Docker Compose is required" >&2; exit 2; }
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# shellcheck source=../../../../e2e-tests/harness-lib.sh
source "$ROOT/e2e-tests/harness-lib.sh"
harness_lock_acquire "dev-realm-smoke"
if [[ -z "${COMPOSE_PROJECT_NAME:-}" ]]; then
  harness_project_name "$ROOT"
  COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME}-devsmoke"
fi
export COMPOSE_PROJECT_NAME
harness_port_defaults
say "compose project $COMPOSE_PROJECT_NAME"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/dlap-devsmoke.XXXXXX")"
rnd() { "$PY" -c 'import secrets; print(secrets.token_hex(24))'; }
KC_URL="http://localhost:${DLAP_PORT_KEYCLOAK}"
ISSUER="$KC_URL/realms/dlap"
KC_ADMIN_PASSWORD="$(rnd)"
BACKEND_SECRET="$(rnd)"
GATEWAY_SECRET="$(rnd)"
cat > "$WORK/env" <<EOF
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=$KC_ADMIN_PASSWORD
DLAP_BACKEND_CLIENT_SECRET=$BACKEND_SECRET
DLAP_MCP_GATEWAY_CLIENT_SECRET=$GATEWAY_SECRET
POSTGRES_PASSWORD=$(rnd)
DLAP_ENCRYPTION_KEY=$("$PY" -c 'import secrets; print(secrets.token_hex(32))')
GCP_PROJECT_ID=devsmoke-project
COMPOSE_PROFILES=
EOF
COMPOSE=(docker compose --env-file "$(native "$WORK/env")"
  -f "$(native "$DEV/docker-compose.yml")" -f "$(native "$DEV/docker-compose.microservices.yml")")

cleanup() {
  if (( KEEP )); then
    say "kept the project; remove it with: ${COMPOSE[*]} down -v"
  else
    "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
    rm -rf "$WORK"
  fi
  harness_lock_release
}
trap cleanup EXIT
harness_reset_stack

say "starting the quickstart keycloak"
if ! "${COMPOSE[@]}" up -d --no-deps keycloak >"$WORK/up.log" 2>&1; then
  cat "$WORK/up.log" >&2; echo "compose up failed" >&2; exit 2
fi
KC="$("${COMPOSE[@]}" ps -q keycloak)"
healthy=no
for ((i = 0; i < 80; i++)); do
  state="$(docker inspect -f '{{.State.Health.Status}}' "$KC" 2>/dev/null || echo missing)"
  [[ "$state" == "healthy" ]] && { healthy=yes; break; }
  [[ "$state" == "missing" ]] && break
  sleep 3
done
for ((i = 0; i < 60; i++)); do
  [[ "$(curl -s -o /dev/null -w '%{http_code}' "$ISSUER/.well-known/openid-configuration")" == 200 ]] && break
  sleep 2
done

json() { "$PY" -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
claims() { "$PY" -c "
import base64, json, sys
token = json.load(sys.stdin)['access_token']
payload = token.split('.')[1]
payload += '=' * (-len(payload) % 4)
print(json.dumps(json.loads(base64.urlsafe_b64decode(payload))))"; }
AUD_PY="(lambda a: ','.join(sorted(a if isinstance(a, list) else [a])))(d.get('aud', []))"
token() { # token <form fields...>: the token endpoint's JSON
  curl -s "$@" "$ISSUER/protocol/openid-connect/token"
}

ADMIN_TOKEN="$(curl -s -d grant_type=password -d client_id=admin-cli -d username=admin \
  --data-urlencode "password=$KC_ADMIN_PASSWORD" "$KC_URL/realms/master/protocol/openid-connect/token" \
  | json "d['access_token']" 2>/dev/null)"
[[ -n "$ADMIN_TOKEN" ]] || { docker logs "$KC" 2>&1 | tail -30 >&2; echo "no master-realm admin token" >&2; exit 2; }
api() { curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$KC_URL/admin/realms$1"; }

say "checks"
info="$(api /../serverinfo)"
check D01 "Keycloak 26.8.1" "26.8.1" "$(json "d['systemInfo']['version']" <<<"$info")"
check D02 "the scripts feature is off" "False" \
  "$(json "any(f['name'] == 'SCRIPTS' and f['enabled'] for f in d['features'])" <<<"$info")"
check D03 "healthy through the management-port health check" "yes" "$healthy"
people="$(api '/dlap/users?briefRepresentation=true&max=100' \
  | json "','.join(sorted(u['username'] for u in d if not u['username'].startswith('service-account-')))")"
check D04 "the dev seed is kept" "admin,cdo,dataengineer,datascientist,director" "$people"
check D05 "no identity provider" "0" "$(api /dlap/identity-provider/instances | json 'len(d)')"

harness="$(token -d grant_type=password -d client_id=dlap-backend --data-urlencode "client_secret=$BACKEND_SECRET" \
  -d username=admin --data-urlencode 'password=Password123!' | claims)"
check D06 "harness password grant: sub, ROLE_ADMIN, audience dlap-api" "True True dlap-api" \
  "$(json "str(bool(d.get('sub'))) + ' ' + str('ROLE_ADMIN' in d.get('roles', [])) + ' ' + $AUD_PY" <<<"$harness")"
svc="$(token -d grant_type=client_credentials -d client_id=dlap-backend --data-urlencode "client_secret=$BACKEND_SECRET" | claims)"
check D07 "service token: sub, ROLE_SERVICE_ACCOUNT, audience dlap-api" "True True dlap-api" \
  "$(json "str(bool(d.get('sub'))) + ' ' + str('ROLE_SERVICE_ACCOUNT' in d.get('roles', [])) + ' ' + $AUD_PY" <<<"$svc")"
gw="$(token -d grant_type=client_credentials -d client_id=dlap-mcp-gateway --data-urlencode "client_secret=$GATEWAY_SECRET" | claims)"
check D08 "gateway token: audience dlap-mcp-pep only" "dlap-mcp-pep" "$(json "$AUD_PY" <<<"$gw")"
check D09 "dlap-ui refuses the password grant" "unauthorized_client" \
  "$(token -d grant_type=password -d client_id=dlap-ui -d username=admin --data-urlencode 'password=Password123!' | json "d.get('error')")"
out="$("$PY" "$LOGIN_PY" --issuer "$ISSUER" --client-id dlap-ui --redirect-uri "http://localhost:3000/" \
  --username admin --password 'Password123!')"; rc=$?
check D10 "browser sign-in on dlap-ui: no OTP, ROLE_ADMIN" "0 sign-in True" \
  "$rc $(json "' '.join(d['steps']) + ' ' + str('ROLE_ADMIN' in (d['claims'] or {}).get('roles', []))" <<<"$out")"
check D11 "the published secret is refused, so the chosen one reached the import" "unauthorized_client" \
  "$(token -d grant_type=client_credentials -d client_id=dlap-backend -d client_secret=change-me-dev-only | json "d.get('error')")"

# D12: a client added later (admin console or API, as harnesses and operators do) gets the realm's
# default scopes. Without basic among them its tokens have no sub, and the PEP and the services bind
# nothing to the caller.
LATE_SECRET="late-$("$PY" -c 'import secrets; print(secrets.token_hex(12))')"
created="$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' "$KC_URL/admin/realms/dlap/clients" \
  -d "{\"clientId\":\"devsmoke-late\",\"publicClient\":false,\"secret\":\"$LATE_SECRET\",\"serviceAccountsEnabled\":true,\
\"standardFlowEnabled\":false}")"
late="$(token -d grant_type=client_credentials -d client_id=devsmoke-late --data-urlencode "client_secret=$LATE_SECRET" | claims)"
check D12 "a client added later gets the basic scope: its tokens carry sub" "201 True" \
  "$created $(json "str(bool(d.get('sub')))" <<<"$late")"

echo
say "$PASS passed, $FAIL failed${FAILED[*]:+: ${FAILED[*]}}"
(( FAIL == 0 ))
