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
# Pilot login smoke (IAM-02).
#
# Starts PostgreSQL and the production Keycloak from the real pilot compose files (the quickstart pair
# plus every docker-compose.pilot.*.yml but the TLS one, in the order up.sh applies them), with throwaway secrets, and
# signs in the way people will:
#
#   K01  the image runs `kc.sh start --optimized` (no build step at start)
#   K02  Keycloak keeps its data in PostgreSQL (database "keycloak", role "keycloak")
#   K03  the scripts, impersonation, device, CIBA and Kerberos features are off
#   K04  the template seeds no people and no identity provider
#   K05  one issuer: the public URL, also on the back channel http://keycloak:8080
#   K06  the machine clients: client credentials only, ROLE_SERVICE_ACCOUNT, no refresh token, no
#        password grant on any client; dlap-backend tokens carry the audience dlap-api, dlap-mcp-gateway
#        tokens only dlap-mcp-pep
#   K07  the admin API answers on the back channel (what da-admin-service calls)
#   K07b the realm's sslRequired is external here (the TLS overlay, left out of this run, makes it all)
#   K08  a ROLE_VIEWER account signs in with a password only; its token carries sub, DLAP roles only and
#        the audience dlap-api
#   K09  ROLE_ADMIN, ROLE_CDO and ROLE_POLICY_MANAGER must set up OTP before any token, and are asked
#        for the code at every later sign-in
#   K10  an OIDC identity provider configured as the Okta and Entra guide says: a viewer signs in
#        without OTP, an administrator must set up OTP after the provider's sign-in
#   K11  login and admin events are stored
#   K12  only 127.0.0.1:8180 is published
#   K13  accounts and OTP devices survive a container recreation, and the import is skipped
#
# With --with-services it also builds and starts kafka, da-catalog and da-discovery and checks:
#
#   S01  da-discovery starts (its OAuth2 client registration no longer discovers the issuer at start)
#   S02  da-catalog, with the issuer and audience validation the pilot base overlay switches on (Q-919), accepts
#        a browser token and a service token (one issuer and the audience dlap-api for both) and still
#        answers 403 to a role it does not admit and 401 to a token that is not one
#   S03  both services got those settings from the overlay files alone: this run adds no override of its own
#        (the strict boot guard of the prod profile refuses a start with either check off)
#
# Runs under the host-wide harness lock (HARN-01) in its own compose project, and removes the project
# and its volumes at the end unless --keep is given. Needs Docker with Compose 2.24 or later, curl and
# python3 (or python). --with-services also needs the jars: ./gradlew :da-catalog:bootJar
# :da-discovery:bootJar (or ./gradlew build -x test).
#
# Usage: deployment/platform/keycloak/tests/pilot-login-smoke.sh [--with-services] [--keep]
# Exit status: 0 when every check passes, 1 when one fails, 2 on a usage or setup problem.
# ---------------------------------------------------------------------------
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../../.." && pwd)"
DEV="$ROOT/deployment/environments/dev"
PILOT="$ROOT/deployment/environments/pilot"
LOGIN_PY="$HERE/oidc_login.py"

WITH_SERVICES=0
KEEP=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-services) WITH_SERVICES=1; shift ;;
    --keep) KEEP=1; shift ;;
    -h|--help) sed -n '/^# Pilot login smoke/,/^# -----/p' "$0" | sed 's/^# \{0,1\}//' | sed '$d'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

say()  { echo "[idsmoke] $*"; }
PASS=0; FAIL=0; FAILED=()
ok()   { PASS=$((PASS + 1)); echo "  PASS $1 $2"; }
bad()  { FAIL=$((FAIL + 1)); FAILED+=("$1"); echo "  FAIL $1 $2${3:+ ($3)}"; }
check() { # check <id> <description> <expected> <actual>
  if [[ "$3" == "$4" ]]; then ok "$1" "$2"; else bad "$1" "$2" "expected '$3', got '$4'"; fi
}

PY="$(command -v python3 || command -v python || true)"
[[ -n "$PY" ]] || { echo "python3 is required" >&2; exit 2; }
"$PY" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' 2>/dev/null \
  || { echo "python 3.8 or later is required" >&2; exit 2; }
command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 2; }
docker compose version >/dev/null 2>&1 || { echo "Docker Compose is required" >&2; exit 2; }
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# shellcheck source=../../../../e2e-tests/harness-lib.sh
source "$ROOT/e2e-tests/harness-lib.sh"
harness_lock_acquire "pilot-login-smoke"
if [[ -z "${COMPOSE_PROJECT_NAME:-}" ]]; then
  harness_project_name "$ROOT"
  COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME}-idsmoke"
fi
export COMPOSE_PROJECT_NAME
say "compose project $COMPOSE_PROJECT_NAME"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/dlap-idsmoke.XXXXXX")"
rnd() { "$PY" -c 'import secrets; print(secrets.token_hex(24))'; }
ID_URL="http://localhost:8180"
APP_URL="http://localhost:8081"
ISSUER="$ID_URL/realms/dlap"
KC_ADMIN="smoke-admin"
KC_ADMIN_PASSWORD="$(rnd)"
KC_DB_PASSWORD="$(rnd)"
BACKEND_SECRET="$(rnd)"
GATEWAY_SECRET="$(rnd)"
cat > "$WORK/env" <<EOF
POSTGRES_PASSWORD=$(rnd)
KEYCLOAK_ADMIN=$KC_ADMIN
KEYCLOAK_ADMIN_PASSWORD=$KC_ADMIN_PASSWORD
KEYCLOAK_DB_PASSWORD=$KC_DB_PASSWORD
DLAP_BACKEND_CLIENT_SECRET=$BACKEND_SECRET
DLAP_MCP_GATEWAY_CLIENT_SECRET=$GATEWAY_SECRET
DLAP_ENCRYPTION_KEY=$("$PY" -c 'import secrets; print(secrets.token_hex(32))')
GCP_PROJECT_ID=idsmoke-project
DLAP_PILOT_ID_URL=$ID_URL
DLAP_PILOT_APP_URL=$APP_URL
COMPOSE_PROFILES=
EOF
# The 13 database role passwords the pilot base overlay requires (SEC-12, ADR-0140), random each run.
bash "$ROOT/deployment/platform/database/gen-db-passwords.sh" "$WORK/env" >/dev/null \
  || { echo "gen-db-passwords.sh failed" >&2; exit 2; }

COMPOSE=(docker compose --env-file "$(native "$WORK/env")"
  -f "$(native "$DEV/docker-compose.yml")" -f "$(native "$DEV/docker-compose.microservices.yml")")
# Every pilot overlay except the transport security one (SEC-06): this smoke test checks the identity
# behaviour over the loopback HTTP port, and tls/tls-e2e.sh checks the same Keycloak under TLS.
while IFS= read -r overlay; do
  COMPOSE+=(-f "$(native "$overlay")")
done < <(find "$PILOT" -maxdepth 1 -type f -name 'docker-compose.pilot.*.yml' ! -name 'docker-compose.pilot.tls.yml' | LC_ALL=C sort)
# The services need no override: the pilot base overlay turns the JWT issuer and audience checks on itself, with the
# issuer of DLAP_PILOT_ID_URL and the audience dlap-api (Q-919), so they start under the strict boot guard of the
# prod profile without the TLS overlay.
grep -q 'docker-compose.pilot.keycloak.yml' <<<"${COMPOSE[*]}" \
  || { echo "the pilot Keycloak overlay is missing" >&2; exit 2; }

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

# --- Start -------------------------------------------------------------------------------------

say "building the optimized Keycloak image and starting postgresql and keycloak"
if ! "${COMPOSE[@]}" up -d --build postgresql keycloak >"$WORK/up.log" 2>&1; then
  cat "$WORK/up.log" >&2
  echo "compose up failed" >&2
  exit 2
fi
KC="$("${COMPOSE[@]}" ps -q keycloak)"
wait_healthy() { # wait_healthy <container> <seconds>
  local i state
  for ((i = 0; i < $2; i += 3)); do
    state="$(docker inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null || echo missing)"
    [[ "$state" == "healthy" ]] && return 0
    [[ "$state" == "missing" ]] && return 1
    sleep 3
  done
  return 1
}
if ! wait_healthy "$KC" 240; then
  docker logs "$KC" 2>&1 | tail -40 >&2
  echo "keycloak did not become healthy" >&2
  exit 2
fi
# The readiness check already waits for the start-up import ("Keycloak Initialized"); the realm
# endpoint is checked as well, so a regression in the health check shows up here as a clear message.
wait_realm() {
  local i
  for ((i = 0; i < 60; i++)); do
    [[ "$(curl -s -o /dev/null -w '%{http_code}' "$ISSUER/.well-known/openid-configuration")" == 200 ]] && return 0
    sleep 2
  done
  return 1
}
wait_realm || { echo "keycloak is healthy but the realm dlap does not answer on $ISSUER" >&2; exit 2; }
say "keycloak healthy"

# --- Helpers -----------------------------------------------------------------------------------

json() { "$PY" -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
claims() { "$PY" -c "
import base64, json, sys
token = json.load(sys.stdin)['access_token']
payload = token.split('.')[1]
payload += '=' * (-len(payload) % 4)
print(json.dumps(json.loads(base64.urlsafe_b64decode(payload))))"; }
admin_token() {
  local body
  body="$(curl -s -d grant_type=password -d client_id=admin-cli --data-urlencode "username=$KC_ADMIN"     --data-urlencode "password=$KC_ADMIN_PASSWORD" "$ID_URL/realms/master/protocol/openid-connect/token")"
  json "d['access_token']" <<<"$body" 2>/dev/null || echo "admin token request answered: ${body:0:200}" >&2
}
api() { # api <method> <path under /admin/realms> [json body]
  local args=(-s -X "$1" -H "Authorization: Bearer $ADMIN_TOKEN" "$ID_URL/admin/realms$2")
  [[ $# -ge 3 ]] && args+=(-H 'Content-Type: application/json' -d "$3")
  curl "${args[@]}"
}
api_status() {
  local args=(-s -o /dev/null -w '%{http_code}' -X "$1" -H "Authorization: Bearer $ADMIN_TOKEN" "$ID_URL/admin/realms$2")
  [[ $# -ge 3 ]] && args+=(-H 'Content-Type: application/json' -d "$3")
  curl "${args[@]}"
}
# HTTP from inside the Keycloak container to its own port 8080 with Host: keycloak:8080, which is what a
# service on the compose network sends. The image has no curl: bash /dev/tcp. Prints the body.
backchannel() { # backchannel <GET|POST> <path> [form body] [bearer token]
  # MSYS_NO_PATHCONV: Git Bash would rewrite the path in P=/realms/... into a Windows path.
  MSYS_NO_PATHCONV=1 docker exec -e M="$1" -e P="$2" -e B="${3:-}" -e T="${4:-}" "$KC" bash -c '
    exec 3<>/dev/tcp/127.0.0.1/8080 || exit 1
    {
      printf "%s %s HTTP/1.1\r\nHost: keycloak:8080\r\nConnection: close\r\n" "$M" "$P"
      [ -n "$T" ] && printf "Authorization: Bearer %s\r\n" "$T"
      if [ "$M" = POST ]; then
        printf "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: %s\r\n" "${#B}"
      fi
      printf "\r\n%s" "$B"
    } >&3
    timeout 10 cat <&3' | tr -d '\r' | sed '1,/^$/d'
}
new_user() { # new_user <username> <group> <password>
  api_status POST /dlap/users "{\"username\":\"$1\",\"enabled\":true,\"email\":\"$1@idsmoke.invalid\",\
\"emailVerified\":true,\"firstName\":\"Smoke\",\"lastName\":\"$2\",\"groups\":[\"/$2\"],\
\"credentials\":[{\"type\":\"password\",\"value\":\"$3\",\"temporary\":false}]}"
}
login() { # login <username> <password> [extra args...]; JSON on stdout, exit status of oidc_login.py
  "$PY" "$LOGIN_PY" --issuer "$ISSUER" --client-id dlap-ui --redirect-uri "$APP_URL/" \
    --username "$1" --password "$2" "${@:3}"
}
next_otp_window() { # sleep into the next 30-second TOTP window, so no code is reused
  local now; now="$("$PY" -c 'import time; print(int(time.time()))')"
  sleep $((30 - now % 30 + 1))
}

ADMIN_TOKEN="$(admin_token)"
[[ -n "$ADMIN_TOKEN" ]] || { echo "no master-realm admin token for $KC_ADMIN" >&2; exit 2; }

# --- K01 to K07: the server ----------------------------------------------------------------------

say "server"
cmd="$(docker inspect -f '{{json .Config.Cmd}}' "$KC")"
if [[ "$cmd" == *'"--optimized"'* ]] && ! harness_log_has "$KC" "Updating the configuration and installing your custom providers"; then
  ok K01 "kc.sh start --optimized, no build step at start ($cmd)"
else
  bad K01 "optimized start" "$cmd"
fi

realms="$("${COMPOSE[@]}" exec -T -e PGPASSWORD="$KC_DB_PASSWORD" postgresql \
  psql -h 127.0.0.1 -U keycloak -d keycloak -tAc "select string_agg(name, ',' order by name) from realm" 2>&1 | tr -d '\r')"
check K02 "realms stored in PostgreSQL database keycloak as role keycloak" "dlap,master" "$realms"

features="$(api GET /../serverinfo | json "','.join(sorted(f['name'] for f in d['features'] if f['enabled'] and f['name'] in ('SCRIPTS','IMPERSONATION','DEVICE_FLOW','CIBA','KERBEROS')))")"
check K03 "scripts, impersonation, device flow, CIBA and Kerberos are off" "" "$features"

people="$(api GET '/dlap/users?briefRepresentation=true&max=100' | json 'len(d)')"
idps="$(api GET /dlap/identity-provider/instances | json 'len(d)')"
check K04 "no seeded people and no identity provider in the template" "0 0" "$people $idps"

front="$(curl -s "$ISSUER/.well-known/openid-configuration" | json "d['issuer']")"
back="$(backchannel GET /realms/dlap/.well-known/openid-configuration | json "d['issuer'] + ' ' + d['token_endpoint']")"
check K05 "one issuer on the front channel and the back channel" \
  "$ISSUER $ISSUER http://keycloak:8080/realms/dlap/protocol/openid-connect/token" "$front $back"

svc="$(backchannel POST /realms/dlap/protocol/openid-connect/token \
  "grant_type=client_credentials&client_id=dlap-backend&client_secret=$BACKEND_SECRET")"
svc_claims="$(claims <<<"$svc")"
gw_claims="$(curl -s -d grant_type=client_credentials -d client_id=dlap-mcp-gateway --data-urlencode "client_secret=$GATEWAY_SECRET" \
  "$ISSUER/protocol/openid-connect/token" | claims)"
got="$(json "d['iss'] + ' ' + ','.join(d['roles']) + ' ' + str('sub' in d)" <<<"$svc_claims") \
$(json "' '.join(['refresh' if 'refresh_token' in d else 'no-refresh'])" <<<"$svc") \
$(json "d['aud'] + ' ' + ','.join(d['roles'])" <<<"$gw_claims")"
check K06a "service tokens: issuer, ROLE_SERVICE_ACCOUNT, sub, no refresh token, PEP audience" \
  "$ISSUER ROLE_SERVICE_ACCOUNT True no-refresh dlap-mcp-pep ROLE_SERVICE_ACCOUNT" "$got"
AUD_PY="(lambda a: ','.join(sorted(a if isinstance(a, list) else [a])))(d.get('aud', []))"
check K06c "audiences: dlap-backend carries dlap-api (the TLS overlay's check), dlap-mcp-gateway only dlap-mcp-pep" \
  "dlap-api dlap-mcp-pep" "$(json "$AUD_PY" <<<"$svc_claims") $(json "$AUD_PY" <<<"$gw_claims")"
grants=""
for client in "dlap-ui" "dlap-backend $BACKEND_SECRET" "dlap-mcp-gateway $GATEWAY_SECRET"; do
  set -- $client
  grants+="$(curl -s -d grant_type=password -d "client_id=$1" ${2:+--data-urlencode "client_secret=$2"} \
    -d username=nobody -d password=nothing "$ISSUER/protocol/openid-connect/token" | json "d.get('error')") "
done
check K06b "no client accepts the password grant" "unauthorized_client unauthorized_client unauthorized_client " "$grants"

bc_admin="$(backchannel POST /realms/master/protocol/openid-connect/token \
  "grant_type=password&client_id=admin-cli&username=$KC_ADMIN&password=$KC_ADMIN_PASSWORD" | json "d['access_token']")"
bc_roles="$(backchannel GET /admin/realms/dlap/roles "" "$bc_admin" | json "','.join(sorted(r['name'] for r in d if r['name'].startswith('ROLE_')))")"
check K07 "admin API on the back channel (da-admin-service)" \
  "ROLE_ADMIN,ROLE_AUDITOR,ROLE_CDO,ROLE_DATA_ANALYST,ROLE_DATA_ENGINEER,ROLE_DATA_SCIENTIST,ROLE_DIRECTOR,ROLE_POLICY_MANAGER,ROLE_SERVICE_ACCOUNT,ROLE_VIEWER" \
  "$bc_roles"

# Without the TLS overlay the realm keeps the template's default (Q-514, D31): plain HTTP from loopback and
# private addresses. The TLS overlay imports it with "all"; tls/smoke-tls.sh reads that value on a TLS pilot.
ADMIN_TOKEN="$(admin_token)"
check K07b "sslRequired of realm dlap is external without the TLS overlay" "external" \
  "$(api GET /dlap | json "d['sslRequired']")"

# --- K08 to K10: people ----------------------------------------------------------------------------

say "sign-in"
PW="Idsmoke-$(rnd)"
created=""
for u in "viewer1 Viewers" "admin1 Administrators" "cdo1 CDOs" "pm1 PolicyManagers" "analyst1 DataAnalysts"; do
  set -- $u
  created+="$(new_user "$1" "$2" "$PW") "
done
[[ "$created" == "201 201 201 201 201 " ]] || { echo "could not create the smoke accounts: $created" >&2; exit 2; }

out="$(login viewer1 "$PW")"; rc=$?
got="$rc $(json "' '.join(d['steps']) + ' ' + ','.join(d['claims']['roles']) + ' ' + str(bool(d['claims'].get('sub'))) + ' ' + d['claims']['iss']" <<<"$out")"
got+=" $(json "(lambda a: ','.join(sorted(a if isinstance(a, list) else [a])))(d['claims'].get('aud', []))" <<<"$out")"
check K08 "viewer: password only, sub present, DLAP roles only, audience dlap-api" "0 sign-in ROLE_VIEWER True $ISSUER dlap-api" "$got"

for u in "admin1 ROLE_ADMIN" "cdo1 ROLE_CDO" "pm1 ROLE_POLICY_MANAGER"; do
  set -- $u
  out="$(login "$1" "$PW" --no-otp)"; rc=$?
  check "K09-$1-a" "$2: no token before an OTP device is set up" "3 otp-setup" "$rc $(json "d['outcome']" <<<"$out")"
  out="$(login "$1" "$PW")"; rc=$?
  check "K09-$1-b" "$2: token after the OTP setup" "0 sign-in otp-setup $2,dlap-mfa-required" \
    "$rc $(json "' '.join(d['steps']) + ' ' + ','.join(sorted(d['claims']['roles']))" <<<"$out")"
  [[ "$1" == "admin1" ]] && ADMIN_OTP="$(json "d['otp_secret']" <<<"$out")"
  out="$(login "$1" "$PW")"; rc=$?
  check "K09-$1-c" "$2: asked for the code at the next sign-in" "3 otp" "$rc $(json "d['outcome']" <<<"$out")"
done
next_otp_window
out="$(login admin1 "$PW" --otp-secret "$ADMIN_OTP")"; rc=$?
check K09-admin1-d "ROLE_ADMIN: a correct code completes the sign-in" "0 sign-in otp" "$rc $(json "' '.join(d['steps'])" <<<"$out")"
out="$(login admin1 "$PW" --otp-secret "0000000000")"; rc=$?
check K09-admin1-e "ROLE_ADMIN: a wrong code gets no token" "1" "$rc"

# The master-realm token lives 60 seconds and the OTP checks above wait for fresh TOTP windows.
ADMIN_TOKEN="$(admin_token)"

# K10: an OIDC identity provider, configured as production/README.md says for Okta and Entra. The
# "corporate" side is a second realm in the same Keycloak, created for this run only.
say "identity provider"
UPW="Upstream-$(rnd)"
status="$(api_status POST "" "{\"realm\":\"idsmoke-upstream\",\"enabled\":true,\
\"clients\":[{\"clientId\":\"dlap-broker\",\"enabled\":true,\"publicClient\":false,\"secret\":\"$GATEWAY_SECRET-up\",\
\"standardFlowEnabled\":true,\"directAccessGrantsEnabled\":false,\"redirectUris\":[\"$ISSUER/broker/corp/endpoint\"],\
\"protocolMappers\":[{\"name\":\"groups\",\"protocol\":\"openid-connect\",\"protocolMapper\":\"oidc-group-membership-mapper\",\
\"config\":{\"claim.name\":\"groups\",\"full.path\":\"false\",\"id.token.claim\":\"true\",\"access.token.claim\":\"true\",\"userinfo.token.claim\":\"true\"}}]}],\
\"groups\":[{\"name\":\"dlap-admins\"},{\"name\":\"dlap-viewers\"}],\
\"users\":[{\"username\":\"alice\",\"enabled\":true,\"email\":\"alice@corp.invalid\",\"emailVerified\":true,\"firstName\":\"Alice\",\"lastName\":\"Corp\",\"groups\":[\"/dlap-admins\"],\"credentials\":[{\"type\":\"password\",\"value\":\"$UPW\",\"temporary\":false}]},\
{\"username\":\"bob\",\"enabled\":true,\"email\":\"bob@corp.invalid\",\"emailVerified\":true,\"firstName\":\"Bob\",\"lastName\":\"Corp\",\"groups\":[\"/dlap-viewers\"],\"credentials\":[{\"type\":\"password\",\"value\":\"$UPW\",\"temporary\":false}]}]}")"
status+=" $(api_status POST /dlap/identity-provider/instances "{\"alias\":\"corp\",\"displayName\":\"Corporate sign-in\",\
\"providerId\":\"oidc\",\"enabled\":true,\"trustEmail\":false,\"storeToken\":false,\
\"firstBrokerLoginFlowAlias\":\"first broker login\",\"postBrokerLoginFlowAlias\":\"dlap post broker mfa\",\
\"config\":{\"clientId\":\"dlap-broker\",\"clientSecret\":\"$GATEWAY_SECRET-up\",\"clientAuthMethod\":\"client_secret_post\",\
\"authorizationUrl\":\"$ID_URL/realms/idsmoke-upstream/protocol/openid-connect/auth\",\
\"tokenUrl\":\"http://localhost:8080/realms/idsmoke-upstream/protocol/openid-connect/token\",\
\"jwksUrl\":\"http://localhost:8080/realms/idsmoke-upstream/protocol/openid-connect/certs\",\
\"issuer\":\"$ID_URL/realms/idsmoke-upstream\",\"validateSignature\":\"true\",\"useJwksUrl\":\"true\",\
\"pkceEnabled\":\"true\",\"pkceMethod\":\"S256\",\"defaultScope\":\"openid profile email\",\"syncMode\":\"FORCE\"}}")"
for m in "dlap-admins ROLE_ADMIN" "dlap-viewers ROLE_VIEWER"; do
  set -- $m
  status+=" $(api_status POST /dlap/identity-provider/instances/corp/mappers "{\"name\":\"$1\",\"identityProviderAlias\":\"corp\",\
\"identityProviderMapper\":\"oidc-role-idp-mapper\",\"config\":{\"syncMode\":\"FORCE\",\"claim\":\"groups\",\"claim.value\":\"$1\",\"role\":\"$2\"}}")"
done
if [[ "$status" != "201 201 201 201" ]]; then
  bad K10 "identity provider setup" "$status"
else
  out="$(login bob "$UPW" --idp-hint corp)"; rc=$?
  check K10a "provider viewer: signs in without OTP and gets ROLE_VIEWER" "0 sign-in ROLE_VIEWER" \
    "$rc $(json "' '.join(d['steps']) + ' ' + ','.join(d['claims']['roles'])" <<<"$out")"
  out="$(login alice "$UPW" --idp-hint corp --no-otp)"; rc=$?
  check K10b "provider administrator: must set up a DLAP OTP device after the provider's sign-in" "3 sign-in otp-setup" \
    "$rc $(json "' '.join(d['steps'])" <<<"$out")"
fi

# --- K11, K12: events, ports -------------------------------------------------------------------------

ADMIN_TOKEN="$(admin_token)"
logins="$(api GET '/dlap/events?type=LOGIN&max=200' | json 'len(d)')"
errors="$(api GET '/dlap/events?type=LOGIN_ERROR&max=200' | json 'len(d)')"
admin_events="$(api GET '/dlap/admin-events?max=200' | json 'len(d)')"
if (( logins >= 5 && errors >= 1 && admin_events >= 5 )); then
  ok K11 "events stored: $logins logins, $errors login errors, $admin_events admin events"
else
  bad K11 "events stored" "$logins logins, $errors login errors, $admin_events admin events"
fi
ports="$(docker port "$KC" | tr -d '\r' | sort | tr '\n' ' ')"
check K12 "only the loopback HTTP port is published" "8080/tcp -> 127.0.0.1:8180 " "$ports"

# --- K13: a recreated container keeps everything ---------------------------------------------------------

say "recreating the keycloak container"
"${COMPOSE[@]}" up -d --force-recreate --no-deps keycloak >"$WORK/recreate.log" 2>&1
KC="$("${COMPOSE[@]}" ps -q keycloak)"
if wait_healthy "$KC" 240 && wait_realm; then
  out="$(login viewer1 "$PW")"; rc1=$?
  out="$(login admin1 "$PW")"; rc2=$?
  skipped=no
  harness_log_has "$KC" "Realm 'dlap' already exists. Import skipped" && skipped=yes
  check K13 "after recreation: import skipped, viewer signs in, admin still has the OTP device" "yes 0 3" "$skipped $rc1 $rc2"
else
  bad K13 "keycloak healthy after recreation" "$(docker logs "$KC" 2>&1 | tail -5 | tr '\n' ' ')"
fi

# --- S01, S02: services ----------------------------------------------------------------------------

# The first error of a container that did not start, and the innermost cause, which names the reason.
start_failure() { # start_failure <container>
  local log; log="$(docker logs "$1" 2>&1 | sed $'s/\x1b\[[0-9;]*m//g')"
  printf '%s | %s' "$(grep -m1 -E ' ERROR ' <<<"$log")" "$(grep -E '^Caused by' <<<"$log" | tail -1)"
}

if (( WITH_SERVICES )); then
  say "building and starting kafka, da-catalog and da-discovery"
  for m in da-catalog da-discovery; do
    ls "$ROOT/$m/build/libs/"*.jar >/dev/null 2>&1 || { bad S00 "$m jar" "run ./gradlew :$m:bootJar first"; }
  done
  if "${COMPOSE[@]}" up -d --build kafka da-catalog da-discovery >"$WORK/services.log" 2>&1; then
    disc="$("${COMPOSE[@]}" ps -q da-discovery)"
    cat_c="$("${COMPOSE[@]}" ps -q da-catalog)"
    if wait_healthy "$disc" 420; then ok S01 "da-discovery started and is healthy"; else
      bad S01 "da-discovery healthy" "$(start_failure "$disc")"; fi
    if wait_healthy "$cat_c" 420; then
      user_token="$(login analyst1 "$PW" --print-token | json "d['access_token']")"
      viewer_token="$(login viewer1 "$PW" --print-token | json "d['access_token']")"
      svc_token="$(json "d['access_token']" <<<"$(backchannel POST /realms/dlap/protocol/openid-connect/token \
        "grant_type=client_credentials&client_id=dlap-backend&client_secret=$BACKEND_SECRET")")"
      codes=""
      for t in "$user_token" "$svc_token" "$viewer_token" "not-a-token"; do
        codes+="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $t" \
          "http://127.0.0.1:8082/api/v1/catalog/assets?page=0&size=1") "
      done
      check S02 "da-catalog with issuer and audience validation: analyst 200, service 200, viewer 403, garbage 401" \
        "200 200 403 401 " "$codes"
      envs=""
      for c in "$cat_c" "$disc"; do
        envs+="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$c" | tr -d '\r' \
          | grep -E '^DLAP_SECURITY_JWT_(VALIDATE_ISSUER|EXPECTED_ISSUER|VALIDATE_AUDIENCE|EXPECTED_AUDIENCE)=' | LC_ALL=C sort | tr '\n' ' ')|"
      done
      want="DLAP_SECURITY_JWT_EXPECTED_AUDIENCE=dlap-api DLAP_SECURITY_JWT_EXPECTED_ISSUER=$ISSUER DLAP_SECURITY_JWT_VALIDATE_AUDIENCE=true DLAP_SECURITY_JWT_VALIDATE_ISSUER=true "
      check S03 "da-catalog and da-discovery run with the issuer and audience checks of the base overlay alone" "$want|$want|" "$envs"
    else
      bad S02 "da-catalog healthy" "$(start_failure "$cat_c")"
    fi
  else
    tail -20 "$WORK/services.log" >&2
    bad S01 "compose up of the services" "see the log above"
  fi
fi

echo
say "$PASS passed, $FAIL failed${FAILED[*]:+: ${FAILED[*]}}"
(( FAIL == 0 ))
