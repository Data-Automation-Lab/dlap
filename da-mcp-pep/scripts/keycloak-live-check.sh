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
#
# ---------------------------------------------------------------------------
# Live check of da-mcp-pep against a real Keycloak (PEP-02, Q-381, Q-382).
#
# The unit and context tests sign their own tokens. This script proves the same rules with tokens
# issued by Keycloak from the shipped realm import (deployment/platform/keycloak/keycloak-import/
# dlap-realm.json), so a wrong audience mapper, client or role in the realm shows up here:
#
#   1. a service-account token of the dlap-mcp-gateway client gets 200 and may name the subject;
#   2. a service-account token without the PEP audience (dlap-backend) gets 401;
#   3. a user token (ROLE_ADMIN, audience added by a throwaway client) naming another principal
#      gets 403 and never reaches the PDP, and a user token naming itself gets 200 with the token's
#      own subject and roles at the PDP;
#   4. health stays open and a request without a token gets 401.
#
# It starts a THROWAWAY Keycloak of its own (docker compose, unique project, own container name and
# port; the dev stack is not touched), the PEP jar, and a recording stub in place of the PDP. The
# whole run holds the host-wide harness lock (HARN-01). Needs docker, curl and python.
#
#   bash da-mcp-pep/scripts/keycloak-live-check.sh
#
# Environment (all optional):
#   PEP_JAR              the PEP boot jar; default: built with ./gradlew :da-mcp-pep:bootJar
#   PEP02_KEYCLOAK_PORT  host port of the throwaway Keycloak (default 18181)
#   PEP02_PEP_PORT       port of the PEP (default 18391)
#   PEP02_PDP_PORT       port of the stub PDP (default 18383)
#   PEP02_KEYCLOAK_IMAGE image (default: the 26.8.1 image the dev compose pins, by digest)
#
# Exit status: 0 when every check passes, 1 otherwise.
# ---------------------------------------------------------------------------
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# shellcheck source=../../e2e-tests/harness-lib.sh
source "$REPO/e2e-tests/harness-lib.sh"
harness_init "pep02-live" "$REPO"

KC_PORT="${PEP02_KEYCLOAK_PORT:-18181}"
PEP_PORT="${PEP02_PEP_PORT:-18391}"
PDP_PORT="${PEP02_PDP_PORT:-18383}"
# The release and digest of deployment/environments/dev/docker-compose.yml (Q-507); test-realm-templates.py
# fails when the two differ.
KC_IMAGE="${PEP02_KEYCLOAK_IMAGE:-quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244}"
KC_URL="http://localhost:$KC_PORT"
PEP_URL="http://localhost:$PEP_PORT"
WORK="$(mktemp -d)"
PEP_PID=""; PDP_PID=""
FAILED=0

# Values for this run only: the Keycloak master admin and the throwaway user.
ADMIN_USER="pep02admin"
ADMIN_PASS="a$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
USER_NAME="pep02-live-user"
USER_PASS="u$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
GATEWAY_SECRET="change-me-dev-only"   # the realm import's published default for dlap-mcp-gateway
BACKEND_SECRET="change-me-dev-only"   # the realm import's published default for dlap-backend

say()  { echo "[pep02-live] $*"; }
fail() { echo "[pep02-live] FAIL: $*" >&2; FAILED=1; }

cleanup() {
  [[ -n "$PEP_PID" ]] && kill "$PEP_PID" 2>/dev/null
  [[ -n "$PDP_PID" ]] && kill "$PDP_PID" 2>/dev/null
  docker compose -f "$WORK/compose.yml" -p "$COMPOSE_PROJECT_NAME" down -v --remove-orphans >/dev/null 2>&1
  rm -rf "$WORK"
  harness_lock_release
}
trap cleanup EXIT

jget() { python -I -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {'d': d}))" "$1"; }

expect_status() { # expect_status <label> <wanted> <got>
  if [[ "$3" == "$2" ]]; then say "ok   $1 -> $3"; else fail "$1 -> $3 (wanted $2)"; fi
}

# --- the jar -----------------------------------------------------------------------------------

if [[ -z "${PEP_JAR:-}" ]]; then
  say "building the PEP jar"
  "$REPO/gradlew" -p "$REPO" :da-mcp-pep:bootJar --no-daemon --console=plain --no-watch-fs --max-workers=2 9>&- \
    >"$WORK/build.log" 2>&1 || { tail -20 "$WORK/build.log" >&2; fail "bootJar failed"; exit 1; }
  PEP_JAR="$(ls "$REPO"/da-mcp-pep/build/libs/*.jar | grep -v plain | head -n 1)"
fi
[[ -f "$PEP_JAR" ]] || { fail "no PEP jar at $PEP_JAR"; exit 1; }

# --- throwaway Keycloak, started from the shipped realm import ---------------------------------

REALM_DIR="$(cygpath -m "$REPO/deployment/platform/keycloak/keycloak-import" 2>/dev/null || echo "$REPO/deployment/platform/keycloak/keycloak-import")"
cat >"$WORK/compose.yml" <<YML
services:
  keycloak:
    image: ${KC_IMAGE}
    container_name: ${COMPOSE_PROJECT_NAME}-keycloak
    command: 'start-dev --import-realm'
    volumes:
      - "${REALM_DIR}:/opt/keycloak/data/import:ro"
    environment:
      KC_DB: dev-file
      KC_BOOTSTRAP_ADMIN_USERNAME: ${ADMIN_USER}
      KC_BOOTSTRAP_ADMIN_PASSWORD: ${ADMIN_PASS}
      KC_FEATURES_DISABLED: scripts
      KC_HTTP_PORT: 8080
      KC_HEALTH_ENABLED: "true"
    ports:
      - "${KC_PORT}:8080"
YML
say "starting a throwaway Keycloak on :$KC_PORT (project $COMPOSE_PROJECT_NAME)"
docker compose -f "$WORK/compose.yml" -p "$COMPOSE_PROJECT_NAME" up -d >"$WORK/up.log" 2>&1 \
  || { cat "$WORK/up.log" >&2; fail "compose up failed"; exit 1; }
ready=0
for _ in $(seq 1 90); do
  if curl -fsS "$KC_URL/realms/dlap/.well-known/openid-configuration" >/dev/null 2>&1; then ready=1; break; fi
  sleep 2
done
if [[ $ready != 1 ]]; then
  docker logs "${COMPOSE_PROJECT_NAME}-keycloak" 2>&1 | tail -20 >&2
  fail "Keycloak did not import the realm in 180 s"
  exit 1
fi
say "realm dlap is up"

# --- a throwaway user token source (admin REST on the throwaway instance) -----------------------

ADMIN_TOKEN="$(curl -fsS -d grant_type=password -d client_id=admin-cli -d username="$ADMIN_USER" \
  --data-urlencode password="$ADMIN_PASS" "$KC_URL/realms/master/protocol/openid-connect/token" | jget "d['access_token']")"
[[ -n "$ADMIN_TOKEN" ]] || { fail "no Keycloak admin token"; exit 1; }
kc_admin() { curl -fsS -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' "$@"; }

kc_admin -X POST "$KC_URL/admin/realms/dlap/clients" -d '{
  "clientId": "pep02-live-user", "publicClient": true, "directAccessGrantsEnabled": true,
  "standardFlowEnabled": false, "protocol": "openid-connect", "defaultClientScopes": ["basic", "dlap"],
  "protocolMappers": [{"name": "dlap-mcp-pep-audience", "protocol": "openid-connect",
    "protocolMapper": "oidc-audience-mapper",
    "config": {"included.custom.audience": "dlap-mcp-pep", "id.token.claim": "false", "access.token.claim": "true"}}]
}' || { fail "cannot create the throwaway user client"; exit 1; }
kc_admin -X POST "$KC_URL/admin/realms/dlap/users" -d "{
  \"username\": \"$USER_NAME\", \"enabled\": true, \"emailVerified\": true,
  \"firstName\": \"Pep\", \"lastName\": \"Live\", \"email\": \"pep02-live@example.test\",
  \"credentials\": [{\"type\": \"password\", \"value\": \"$USER_PASS\", \"temporary\": false}]
}" || { fail "cannot create the throwaway user"; exit 1; }
USER_ID="$(kc_admin "$KC_URL/admin/realms/dlap/users?username=$USER_NAME&exact=true" | jget "d[0]['id']")"
ADMIN_ROLE="$(kc_admin "$KC_URL/admin/realms/dlap/roles/ROLE_ADMIN")"
kc_admin -X POST "$KC_URL/admin/realms/dlap/users/$USER_ID/role-mappings/realm" -d "[$ADMIN_ROLE]" \
  || { fail "cannot grant ROLE_ADMIN to the throwaway user"; exit 1; }

token_cc() { # token_cc <client id> <secret>
  curl -fsS -d grant_type=client_credentials -d client_id="$1" --data-urlencode client_secret="$2" \
    "$KC_URL/realms/dlap/protocol/openid-connect/token" | jget "d['access_token']"
}
claims() { # claims <jwt>: aud, roles, sub
  python -I -c "
import base64,json,sys
p=sys.argv[1].split('.')[1]; p+='='*(-len(p)%4)
c=json.loads(base64.urlsafe_b64decode(p))
print('aud=%s realm_roles=%s roles=%s sub=%s' % (c.get('aud'), c.get('realm_access',{}).get('roles'), c.get('roles'), c.get('sub')))" "$1"
}
subject_of() { # subject_of <jwt>
  python -I -c "
import base64,json,sys
p=sys.argv[1].split('.')[1]; p+='='*(-len(p)%4)
print(json.loads(base64.urlsafe_b64decode(p))['sub'])" "$1"
}

GATEWAY_TOKEN="$(token_cc dlap-mcp-gateway "$GATEWAY_SECRET")"
BACKEND_TOKEN="$(token_cc dlap-backend "$BACKEND_SECRET")"
USER_TOKEN="$(curl -fsS -d grant_type=password -d client_id=pep02-live-user -d username="$USER_NAME" \
  --data-urlencode password="$USER_PASS" "$KC_URL/realms/dlap/protocol/openid-connect/token" | jget "d['access_token']")"
[[ -n "$GATEWAY_TOKEN" && -n "$BACKEND_TOKEN" && -n "$USER_TOKEN" ]] || { fail "a token request failed"; exit 1; }
say "gateway token : $(claims "$GATEWAY_TOKEN")"
say "backend token : $(claims "$BACKEND_TOKEN")"
say "user token    : $(claims "$USER_TOKEN")"
GATEWAY_SUB="$(subject_of "$GATEWAY_TOKEN")"

# --- stub PDP and the PEP -----------------------------------------------------------------------

cat >"$WORK/stub_pdp.py" <<'PY'
import http.server, json, sys

LOG = sys.argv[2]

class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        if 'chunked' in self.headers.get('Transfer-Encoding', '').lower():
            body = b''
            while True:
                size = int(self.rfile.readline().strip() or b'0', 16)
                if size == 0:
                    self.rfile.readline()
                    break
                body += self.rfile.read(size)
                self.rfile.readline()
        else:
            body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        with open(LOG, 'a', encoding='utf-8') as f:
            f.write(body.decode('utf-8') + '\n')
        out = json.dumps({'decision': True, 'context': {'reason_admin': {'en': 'stub allow'}}}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *args):
        pass

http.server.HTTPServer(('127.0.0.1', int(sys.argv[1])), Handler).serve_forever()
PY
PDP_LOG="$WORK/pdp-requests.jsonl"; : >"$PDP_LOG"
python -I "$WORK/stub_pdp.py" "$PDP_PORT" "$PDP_LOG" &
PDP_PID=$!

JAVA="${JAVA_HOME:-}/bin/java"; [[ -x "$JAVA" ]] || JAVA=java
SERVER_PORT="$PEP_PORT" KEYCLOAK_AUTH_SERVER_URL="$KC_URL" \
DLAP_SECURITY_JWT_EXPECTED_ISSUER="$KC_URL/realms/dlap" DLAP_SECURITY_JWT_EXPECTED_AUDIENCE="dlap-mcp-pep" \
DLAP_PDP_BASE_URL="http://127.0.0.1:$PDP_PORT" DLAP_MCPPEP_CACHE_TTL=0 \
  "$JAVA" -jar "$(cygpath -m "$PEP_JAR" 2>/dev/null || echo "$PEP_JAR")" >"$WORK/pep.log" 2>&1 9>&- &
PEP_PID=$!
up=0
for _ in $(seq 1 60); do
  if [[ "$(curl -s -o /dev/null -w '%{http_code}' "$PEP_URL/actuator/health")" == 200 ]]; then up=1; break; fi
  sleep 2
done
if [[ $up != 1 ]]; then
  tail -30 "$WORK/pep.log" >&2
  fail "the PEP did not start in 120 s"
  exit 1
fi

# --- the checks ---------------------------------------------------------------------------------

authorize() { # authorize <bearer or -> <principal or ->: prints the HTTP status, the body is left in $WORK/resp
  local bearer="$1" principal="$2" auth=() who=""
  [[ "$bearer" != "-" ]] && auth=(-H "Authorization: Bearer $bearer")
  [[ "$principal" != "-" ]] && who="\"principalId\":\"$principal\","
  curl -s -o "$WORK/resp" -w '%{http_code}' "${auth[@]}" -H 'Content-Type: application/json' \
    -d "{${who}\"roles\":[\"VIEWER\"],\"tier\":\"EXECUTIVE\",\"tool\":\"query\",\"resourceUri\":\"bq://p/d/t-$RANDOM$RANDOM\",\"action\":\"read\",\"context\":{}}" \
    "$PEP_URL/mcp/v1/authorize"
}
pdp_calls() { wc -l <"$PDP_LOG" | tr -d ' '; }
last_pdp() { tail -n 1 "$PDP_LOG" | tr -d '\r' | jget "$1"; }

expect_status "health, no token" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$PEP_URL/actuator/health")"
expect_status "authorize, no token" 401 "$(authorize - agent-live-1)"

# 1. service account of the new client
before=$(pdp_calls)
expect_status "service account (dlap-mcp-gateway) names agent-live-1" 200 "$(authorize "$GATEWAY_TOKEN" agent-live-1)"
[[ "$(pdp_calls)" == $((before + 1)) ]] || fail "the PDP was not asked once"
[[ "$(last_pdp "d['subject']['id']")" == "agent-live-1" ]] || fail "the PDP saw subject $(last_pdp "d['subject']['id']"), wanted agent-live-1"
[[ "$(last_pdp "d['subject']['properties']['roles']")" == "['VIEWER']" ]] || fail "the PDP did not get the gateway's roles"
expect_status "service account names nobody" 200 "$(authorize "$GATEWAY_TOKEN" -)"
[[ "$(last_pdp "d['subject']['id']")" == "$GATEWAY_SUB" ]] || fail "a nameless service-account request was not bound to the token subject"

# 2. a token without the audience
expect_status "service account without the PEP audience (dlap-backend)" 401 "$(authorize "$BACKEND_TOKEN" agent-live-1)"

# 3. a user token
before=$(pdp_calls)
expect_status "user token names another principal" 403 "$(authorize "$USER_TOKEN" someone-else)"
grep -q '"allow":false' "$WORK/resp" || fail "the refusal is not the deny shape: $(cat "$WORK/resp")"
[[ "$(pdp_calls)" == "$before" ]] || fail "a refused request reached the PDP"
expect_status "user token names itself" 200 "$(authorize "$USER_TOKEN" "$USER_ID")"
[[ "$(last_pdp "d['subject']['id']")" == "$USER_ID" ]] || fail "the PDP saw subject $(last_pdp "d['subject']['id']"), wanted the user id"
[[ "$(last_pdp "sorted(d['subject']['properties']['roles'])")" == *"'ADMIN'"* ]] || fail "the PDP did not get the token's ADMIN role"
[[ "$(last_pdp "d['subject']['properties']['roles']")" != *"VIEWER"* ]] || fail "the body's VIEWER role reached the PDP for a user token"
[[ "$(last_pdp "d['subject']['properties'].get('tier')")" == "None" ]] || fail "the body's tier reached the PDP for a user token"
expect_status "user token names nobody" 200 "$(authorize "$USER_TOKEN" -)"
[[ "$(last_pdp "d['subject']['id']")" == "$USER_ID" ]] || fail "a nameless user request was not bound to the token subject"

if [[ $FAILED == 0 ]]; then
  say "ALL CHECKS PASSED"
else
  say "SOME CHECKS FAILED"
  echo "--- requests the stub PDP received" >&2
  cat "$PDP_LOG" >&2
  tail -20 "$WORK/pep.log" >&2
fi
exit $FAILED
