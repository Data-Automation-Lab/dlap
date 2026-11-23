#!/usr/bin/env bash
#
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
# DLAP hosted-demo identity check (DEMO-02).
#
# Named checks of the demo Keycloak and of the services' issuer check. Every check has an id, a
# one-line claim and a pass or fail, and every one can fail.
#
#   S-*  static: dlap-demo-realm.json and the merged compose model. Starts nothing.
#   K-*  live Keycloak: version, realm settings as imported, refused flows, OTP for ROLE_ADMIN, brute
#        force protection, no dev users.
#   I-*  live issuer and audience: a token of the public issuer and the audience dlap-api gets 200 from
#        one read endpoint of every running service; a token of another issuer gets 401, both from
#        another realm (other keys) and signed with a key the realm publishes (only the issuer
#        differs); a token without the audience dlap-api gets 401 (Q-913), both from a client that has
#        no audience mapper and signed with a published key (only the audience differs).
#   A-*  live AuthZEN: the PDP answers only ROLE_ADMIN (founder) and the service account.
#
# Usage:  verify-issuer.sh [--env-file FILE] [--static] [--only PREFIX]
#   --env-file FILE  the demo environment file (default: ../.env.demo, or $DEMO_ENV_FILE; --static
#                    falls back to ../.env.demo.example)
#   --static         S-* only: no running stack needed, only docker compose and python
#   --only PREFIX    run the checks whose id starts with PREFIX (S, K, I, A, or for example K-06)
#   --purge          first delete every verify-issuer-* object that an interrupted earlier run left
#
# The live checks need the running demo stack (../up.sh, then ../scripts/seed.sh or up.sh alone) and
# run on the demo host or the rehearsal machine. They reach:
#   - Keycloak and the app through Caddy, at their public names, resolved to the local Caddy
#     (curl --resolve), so the TLS names and the issuer are the public ones;
#   - the Keycloak admin API on the loopback port (DLAP_PORT_KEYCLOAK), with the master-realm admin
#     of the environment file (KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD; DEMO_VERIFY_ADMIN_TOTP when
#     that account has an OTP device);
#   - each service directly on the internal network, through the gateway container's wget, because
#     not every service is routed by the gateway (da-logingest is not).
#
# While they run, the live checks add to the realm, and remove again on exit (also on failure or
# Ctrl-C): confidential clients with a service account (one with ROLE_ADMIN, one with ROLE_ADMIN and no audience, one with every other
# human role), three users (one with ROLE_ADMIN, one with ROLE_VIEWER, one for the lockout), and a
# passive RSA key whose private half exists only in a temporary folder of this run. Every name starts
# with "verify-issuer-". Run it when no visitor depends on the demo, for example after the nightly
# reset; it takes about a minute.
#
# Needs: bash, curl, openssl, python3 (or python), docker with compose 2.24 or later.
# Exit:  0 every check passed; 1 a check failed; 2 the checks could not run.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../scripts/lib.sh
source "$SCRIPT_DIR/../scripts/lib.sh"

STATIC=0
PURGE=0
ONLY=""
ENV_GIVEN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) [ $# -ge 2 ] || { echo "verify-issuer.sh: --env-file needs a file name" >&2; exit 2; }; DEMO_ENV_FILE="$2"; ENV_GIVEN=1; shift 2 ;;
    --static) STATIC=1; shift ;;
    --purge) PURGE=1; shift ;;
    --only) [ $# -ge 2 ] || { echo "verify-issuer.sh: --only needs a prefix" >&2; exit 2; }; ONLY="$2"; shift 2 ;;
    -h|--help) awk '/^# DLAP hosted-demo identity check/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "verify-issuer.sh: unknown argument: $1" >&2; exit 2 ;;
  esac
done
if [ "$STATIC" = 1 ] && [ "$ENV_GIVEN" = 0 ] && [ ! -f "$DEMO_ENV_FILE" ]; then
  DEMO_ENV_FILE="$DEMO_DIR/.env.demo.example"
fi
[ -f "$DEMO_ENV_FILE" ] || { echo "verify-issuer.sh: environment file not found: $DEMO_ENV_FILE" >&2; exit 2; }

PYTHON="${PYTHON:-}"
if [ -z "$PYTHON" ]; then
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import json' >/dev/null 2>&1; then PYTHON="$candidate"; break; fi
  done
fi
[ -n "$PYTHON" ] || { echo "verify-issuer.sh: python3 is required" >&2; exit 2; }
for tool in curl openssl docker; do
  command -v "$tool" >/dev/null 2>&1 || { echo "verify-issuer.sh: $tool is required" >&2; exit 2; }
done

# The model must not depend on the caller's shell, except for DEMO_EXTRA_COMPOSE (lib.sh), which adds
# compose files, for example a deliberately broken one to see a check fail.
unset COMPOSE_PROFILES
demo_init "$DEMO_ENV_FILE"

# DEMO_KEYCLOAK_REALM_FILE checks another realm file statically (a broken copy, to see a check fail).
REALM_FILE="${DEMO_KEYCLOAK_REALM_FILE:-$SCRIPT_DIR/dlap-demo-realm.json}"
# A native path (drive letter on Windows): curl, openssl and python get file names from it, and lib.sh
# turns off the Git Bash path conversion.
TMP="$(demo_native_path "$(mktemp -d)")"
chmod 700 "$TMP"
PASS=0
FAIL=0
FAILED=()

# --- Reporting ----------------------------------------------------------------------------------

selected() { [ -z "$ONLY" ] || [[ "$1" == "$ONLY"* ]]; }
pass() { PASS=$((PASS + 1)); printf 'PASS %-6s %s\n' "$1" "$2"; }
fail() { FAIL=$((FAIL + 1)); FAILED+=("$1"); printf 'FAIL %-6s %s\n       %s\n' "$1" "$2" "$3"; }
# check <id> <claim> <actual> <expected>: equality check with both values in the failure text.
check() { if [ "$3" = "$4" ]; then pass "$1" "$2"; else fail "$1" "$2" "got '$3', want '$4'"; fi; }

# --- Helpers ------------------------------------------------------------------------------------

# Evaluate a Python expression on JSON read from stdin (d), print strings bare and the rest as JSON.
pyj() {
  "$PYTHON" -c '
import json, sys
d = json.load(sys.stdin)
r = eval(sys.argv[1])
print(r if isinstance(r, str) else json.dumps(r))' "$1" 2>/dev/null
}
# The claims of a JWT, as JSON.
jwt_claims() {
  printf '%s' "$1" | "$PYTHON" -c '
import base64, sys
p = sys.stdin.read().split(".")[1]
print(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)).decode())' 2>/dev/null
}
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
rand_hex() { openssl rand -hex "$1"; }

ID_HOST="$(demo_cfg DEMO_ID_HOST id.localhost)"
APP_HOST="$(demo_cfg DEMO_APP_HOST demo.localhost)"
SUFFIX="$(demo_cfg DEMO_ID_PORT_SUFFIX "")"
ID_ORIGIN="https://$ID_HOST$SUFFIX"
APP_ORIGIN="https://$APP_HOST$SUFFIX"
ISSUER="$ID_ORIGIN/realms/dlap"

# --- S: static ----------------------------------------------------------------------------------

static_checks() {
  local model="$TMP/model.json" out
  if ! demo_dc --profile plus config --format json >"$model" 2>"$TMP/config.err"; then
    fail S-00 "the merged compose model renders" "$(tr -d '\r' <"$TMP/config.err" | head -n 5)"
    return
  fi
  out="$("$PYTHON" - "$(demo_native_path "$REALM_FILE")" "$(demo_native_path "$model")" "$ISSUER" "$APP_ORIGIN" <<'PY'
import json, re, sys

realm = json.load(open(sys.argv[1], encoding="utf-8"))
model = json.load(open(sys.argv[2], encoding="utf-8"))["services"]
issuer, app_origin = sys.argv[3], sys.argv[4]
results = []

def check(cid, claim, ok, detail=""):
    results.append((cid, claim, bool(ok), detail))

def env_of(service):
    environment = service.get("environment") or {}
    if isinstance(environment, list):
        return dict(item.split("=", 1) for item in environment if "=" in item)
    return environment

clients = {c["clientId"]: c for c in realm.get("clients", [])}
ui = clients.get("dlap-ui", {})
backend = clients.get("dlap-backend", {})

check("S-01", "self-registration is off", realm.get("registrationAllowed") is False)
check("S-02", "reset password is off", realm.get("resetPasswordAllowed") is False)
bad = [c for c, r in clients.items() if r.get("implicitFlowEnabled") is not False or r.get("directAccessGrantsEnabled") is not False]
check("S-03", "no client allows the implicit flow or the password grant", not bad, f"clients: {bad}")
uris = ui.get("redirectUris") or []
exact = uris == ["${DLAP_DEMO_APP_URL}/", "${DLAP_DEMO_APP_URL}/silent-check-sso.html"]
check("S-04", "dlap-ui: public, PKCE S256, exactly the two redirect URIs of the SPA, one web origin",
      ui.get("publicClient") is True and ui.get("serviceAccountsEnabled") is False
      and (ui.get("attributes") or {}).get("pkce.code.challenge.method") == "S256"
      and exact and not any("*" in u for u in uris) and ui.get("webOrigins") == ["${DLAP_DEMO_APP_URL}"],
      f"redirectUris {uris}, webOrigins {ui.get('webOrigins')}")
humans = [u.get("username") for u in realm.get("users", []) if not u.get("serviceAccountClientId")]
check("S-05", "no seeded user: the only account is the service account of dlap-backend", not humans and len(realm.get("users", [])) == 1, f"users {humans}")

flows = {f["alias"]: f for f in realm.get("authenticationFlows", [])}
configs = {c["alias"]: c.get("config", {}) for c in realm.get("authenticatorConfig", [])}
def otp_for_admin():
    browser = flows.get(realm.get("browserFlow", ""))
    if not browser:
        return False
    for e in browser["authenticationExecutions"]:
        if e.get("flowAlias") in flows and e.get("requirement") == "ALTERNATIVE":
            forms = flows[e["flowAlias"]]["authenticationExecutions"]
            if not any(x.get("authenticator") == "auth-username-password-form" and x.get("requirement") == "REQUIRED" for x in forms):
                continue
            for x in forms:
                sub = flows.get(x.get("flowAlias") or "")
                if sub and x.get("requirement") == "CONDITIONAL":
                    steps = sorted(sub["authenticationExecutions"], key=lambda s: s.get("priority", 0))
                    cond = [s for s in steps if s.get("authenticator") == "conditional-user-role"]
                    otp = [s for s in steps if s.get("authenticator") == "auth-otp-form" and s.get("requirement") == "REQUIRED"]
                    if cond and otp and configs.get(cond[0].get("authenticatorConfig"), {}).get("condUserRole") == "ROLE_ADMIN" \
                            and configs.get(cond[0].get("authenticatorConfig"), {}).get("negate", "false") == "false":
                        return True
    return False
check("S-06", "the browser flow asks ROLE_ADMIN for an OTP after the password; no identity provider",
      otp_for_admin() and not realm.get("identityProviders"))
check("S-07", "brute-force protection is on (lockout after at most 10 failures, temporary)",
      realm.get("bruteForceProtected") is True and 0 < int(realm.get("failureFactor", 0)) <= 10 and realm.get("permanentLockout") is False)
def has_roles_mapper(client):
    return any(m.get("protocolMapper") == "oidc-usermodel-realm-role-mapper" and m.get("config", {}).get("claim.name") == "roles"
               and m.get("config", {}).get("access.token.claim") == "true" for m in client.get("protocolMappers", []))
check("S-08", "both clients carry the top-level roles mapper", has_roles_mapper(ui) and has_roles_mapper(backend))
default_role = next((r for r in realm["roles"]["realm"] if r["name"] == realm.get("defaultRole", {}).get("name")), {})
sa = next((u for u in realm.get("users", []) if u.get("serviceAccountClientId") == "dlap-backend"), {})
check("S-09", "the default role grants no DLAP role; the service account holds ROLE_SERVICE_ACCOUNT only",
      default_role.get("composite") is False and sa.get("realmRoles") == ["ROLE_SERVICE_ACCOUNT"]
      and (backend.get("secret") or "") == "${DLAP_BACKEND_CLIENT_SECRET}")

kc = model.get("keycloak", {})
kenv = env_of(kc)
image = kc.get("image", "")
check("S-10", "Keycloak: a 26.x image pinned by digest, production mode, Postgres, the demo realm",
      re.fullmatch(r"quay\.io/keycloak/keycloak:26\.\d+\.\d+@sha256:[0-9a-f]{64}", image) is not None
      and (kc.get("command") or [None])[0] == "start" and kenv.get("KC_DB") == "postgres"
      and any(v.get("target") == "/opt/keycloak/data/import/dlap-realm.json" and (v.get("source") or "").replace("\\", "/").endswith("/demo/keycloak/dlap-demo-realm.json") for v in kc.get("volumes") or [])
      and not any("keycloak-import" in (v.get("source") or "") for v in kc.get("volumes") or []),
      f"image {image}, command {kc.get('command')}")
check("S-11", "Keycloak's front-end URL is the public issuer's origin; back-channel URLs stay dynamic",
      kenv.get("KC_HOSTNAME") + "/realms/dlap" == issuer and kenv.get("KC_HOSTNAME_BACKCHANNEL_DYNAMIC") == "true"
      and kenv.get("DLAP_DEMO_APP_URL") == app_origin,
      f"KC_HOSTNAME {kenv.get('KC_HOSTNAME')}, DLAP_DEMO_APP_URL {kenv.get('DLAP_DEMO_APP_URL')}")
jvms = [s for s in model if s.startswith("da-")]
wrong = []
for name in jvms:
    env = env_of(model[name])
    if env.get("DLAP_SECURITY_JWT_VALIDATE_ISSUER") != "true" or env.get("DLAP_SECURITY_JWT_EXPECTED_ISSUER") != issuer \
            or env.get("KEYCLOAK_AUTH_SERVER_URL") != "http://keycloak:8080" \
            or env.get("SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI") != "http://keycloak:8080/realms/dlap/protocol/openid-connect/certs" \
            or "dlap.security.jwt" in env.get("SPRING_APPLICATION_JSON", ""):
        wrong.append(name)
check("S-12", f"all {len(jvms)} JVMs check the public issuer and read keys on the internal network, by environment only",
      len(jvms) == 10 and not wrong, f"wrong: {wrong}")

# Q-913: the audience is checked everywhere, the realm writes it, and nothing waives the boot guard.
def audience_mapper(client):
    found = [m for m in client.get("protocolMappers", []) if m.get("protocolMapper") == "oidc-audience-mapper"
             and m.get("config", {}).get("included.custom.audience") == "${DLAP_API_AUDIENCE:dlap-api}"
             and m.get("config", {}).get("access.token.claim") == "true"]
    return len(found) == 1
waived = [n for n in jvms if "DLAP_SECURITY_DEV_SECRET_GUARD_ACCEPTED" in env_of(model[n])
          or "dev-secret-guard" in env_of(model[n]).get("SPRING_APPLICATION_JSON", "")]
no_audience = [n for n in jvms if env_of(model[n]).get("DLAP_SECURITY_JWT_VALIDATE_AUDIENCE") != "true"
               or env_of(model[n]).get("DLAP_SECURITY_JWT_EXPECTED_AUDIENCE") != "dlap-api"]
check("S-13", "every JVM requires the audience dlap-api, both clients carry the audience mapper, no boot-guard waiver is set",
      audience_mapper(ui) and audience_mapper(backend) and not no_audience and not waived and "DLAP_API_AUDIENCE" not in kenv,
      f"mapper ui {audience_mapper(ui)}, backend {audience_mapper(backend)}, audience off or other in {no_audience}, waiver in {waived}")

for cid, claim, ok, detail in results:
    print(("PASS" if ok else "FAIL") + "\t" + cid + "\t" + claim + "\t" + detail)
PY
)" || { fail S-00 "the static checks ran" "python failed"; return; }
  local status cid claim detail
  while IFS=$'\t' read -r status cid claim detail; do
    [ -n "$status" ] || continue
    selected "$cid" || continue
    if [ "$status" = PASS ]; then pass "$cid" "$claim"; else fail "$cid" "$claim" "$detail"; fi
  done < <(printf '%s\n' "$out" | tr -d '\r')
}

# --- Live: plumbing -------------------------------------------------------------------------------

KC_LOCAL="http://127.0.0.1:$(demo_cfg DLAP_PORT_KEYCLOAK 8180)"
HTTPS_PORT="${SUFFIX#:}"; [ -n "$HTTPS_PORT" ] || HTTPS_PORT=443
EDGE_ADDR="$(demo_cfg DEMO_BIND_ADDR 0.0.0.0)"; [ "$EDGE_ADDR" != "0.0.0.0" ] || EDGE_ADDR=127.0.0.1
CURL_EDGE=(--resolve "$ID_HOST:$HTTPS_PORT:$EDGE_ADDR" --resolve "$APP_HOST:$HTTPS_PORT:$EDGE_ADDR")
PREFIX="verify-issuer-$(rand_hex 3)"
BODY="$TMP/body"
CREATED_COMPONENT=""
ADMIN_TOKEN=""
ADMIN_TOKEN_AT=0

# Caddy's local CA in a rehearsal (DEMO_TLS=internal); the system trust store on the demo host.
edge_tls() {
  if [ "$(demo_cfg DEMO_TLS auto)" = "internal" ]; then
    demo_dc exec -T caddy cat /data/caddy/pki/authorities/local/root.crt >"$TMP/caddy-root.crt" 2>/dev/null \
      && [ -s "$TMP/caddy-root.crt" ] || return 1
    CURL_EDGE+=(--cacert "$(demo_native_path "$TMP/caddy-root.crt")")
    # Windows curl (Schannel) asks for a revocation status that a local CA cannot give.
    if curl --version 2>/dev/null | grep -qi schannel; then CURL_EDGE+=(--ssl-no-revoke); fi
  fi
  return 0
}

# edge <curl args...>: a request through Caddy; prints the status, the body is in $BODY.
edge() { curl -s -o "$BODY" -w '%{http_code}' --max-time 20 "${CURL_EDGE[@]}" "$@"; }

admin_token() {
  if [ -z "$ADMIN_TOKEN" ] || (( SECONDS - ADMIN_TOKEN_AT > 40 )); then
    local args=(-d grant_type=password -d client_id=admin-cli --data-urlencode "username=$(demo_cfg KEYCLOAK_ADMIN admin)"
                --data-urlencode "password=$(demo_cfg KEYCLOAK_ADMIN_PASSWORD "")")
    [ -z "${DEMO_VERIFY_ADMIN_TOTP:-}" ] || args+=(--data-urlencode "totp=$DEMO_VERIFY_ADMIN_TOTP")
    ADMIN_TOKEN="$(curl -s --max-time 15 "${args[@]}" "$KC_LOCAL/realms/master/protocol/openid-connect/token" | pyj "d['access_token']")"
    ADMIN_TOKEN_AT=$SECONDS
  fi
  printf '%s' "$ADMIN_TOKEN"
}

# kc <METHOD> <path under /admin/realms/dlap> [JSON body]: prints the status, the body is in $BODY.
kc() {
  local token args=()
  token="$(admin_token)"
  [ $# -lt 3 ] || args=(--data-binary "$3")
  curl -s -o "$BODY" -w '%{http_code}' --max-time 20 -X "$1" -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' "${args[@]}" "$KC_LOCAL/admin/realms/dlap$2"
}
kc_json() { kc GET "$1" >/dev/null; cat "$BODY"; }

role_rep() { kc_json "/roles/$1"; }

# svc_status <service> <path> [token]: the status of a GET sent from the gateway container straight to
# the service on the internal network (busybox wget prints the status line with -S).
svc_status() {
  local svc="$1" path="$2" token="${3:-}" args=(wget -S -q -O /dev/null -T 15)
  [ -z "$token" ] || args+=(--header "Authorization: Bearer $token")
  [[ "$svc" == *:* ]] || svc="$svc:8080"
  demo_dc exec -T gateway "${args[@]}" "http://$svc$path" 2>&1 | tr -d '\r' \
    | grep -o 'HTTP/1\.[01] [0-9][0-9][0-9]' | tail -n 1 | awk '{print $2}'
}

# A confidential client with a service account that holds the given realm roles. Prints the secret. Its
# tokens carry the audience dlap-api, like those of dlap-ui and dlap-backend (the realm file gives them the
# mapper), unless CLIENT_AUDIENCE is set: an empty value leaves the mapper out, another value names it.
make_client() { # make_client <clientId> <role>...
  local cid="$1" secret uid id reps="" role aud="${CLIENT_AUDIENCE-dlap-api}" aud_mapper=""
  shift
  if [ -n "$aud" ]; then
    aud_mapper=",{\"name\":\"audience\",\"protocol\":\"openid-connect\",\"protocolMapper\":\"oidc-audience-mapper\",\"config\":{\"included.custom.audience\":\"$aud\",\"access.token.claim\":\"true\",\"id.token.claim\":\"false\"}}"
  fi
  secret="$(rand_hex 24)"
  kc POST /clients "{\"clientId\":\"$cid\",\"enabled\":true,\"publicClient\":false,\"serviceAccountsEnabled\":true,\"standardFlowEnabled\":false,\"implicitFlowEnabled\":false,\"directAccessGrantsEnabled\":false,\"secret\":\"$secret\",\"defaultClientScopes\":[\"acr\",\"basic\",\"profile\",\"roles\"],\"protocolMappers\":[{\"name\":\"top-level roles\",\"protocol\":\"openid-connect\",\"protocolMapper\":\"oidc-usermodel-realm-role-mapper\",\"config\":{\"multivalued\":\"true\",\"claim.name\":\"roles\",\"jsonType.label\":\"String\",\"access.token.claim\":\"true\"}}$aud_mapper]}" >/dev/null
  id="$(kc_json "/clients?clientId=$cid" | pyj "d[0]['id']")"
  [ -n "$id" ] || return 1
  uid="$(kc_json "/clients/$id/service-account-user" | pyj "d['id']")"
  for role in "$@"; do reps="$reps${reps:+,}$(role_rep "$role")"; done
  [ "$(kc POST "/users/$uid/role-mappings/realm" "[$reps]")" = 204 ] || return 1
  printf '%s' "$secret"
}

# A user with a password and the given realm roles. Prints the user id.
make_user() { # make_user <username> <password> [role...]
  local name="$1" password="$2" id reps="" role
  shift 2
  kc POST /users "{\"username\":\"$name\",\"enabled\":true,\"email\":\"$name@verify.invalid\",\"emailVerified\":true,\"firstName\":\"Verify\",\"lastName\":\"Issuer\",\"credentials\":[{\"type\":\"password\",\"value\":\"$password\",\"temporary\":false}]}" >/dev/null
  id="$(kc_json "/users?username=$name&exact=true" | pyj "d[0]['id']")"
  [ -n "$id" ] || return 1
  for role in "$@"; do reps="$reps${reps:+,}$(role_rep "$role")"; done
  [ -z "$reps" ] || [ "$(kc POST "/users/$id/role-mappings/realm" "[$reps]")" = 204 ] || return 1
  printf '%s' "$id"
}

# client_token <base URL> <clientId> <secret>: a client-credentials token from that token endpoint.
client_token() {
  local base="$1" curl_args=(-s --max-time 15)
  [[ "$base" != https://* ]] || curl_args+=("${CURL_EDGE[@]}")
  curl "${curl_args[@]}" -d grant_type=client_credentials -d "client_id=$2" --data-urlencode "client_secret=$3" \
    "$base/realms/dlap/protocol/openid-connect/token" | pyj "d['access_token']"
}

# browser_login <username> <password>: the SPA's sign-in, by curl, through Caddy. Sets LOGIN_STATUS,
# LOGIN_LOCATION and VERIFIER (the PKCE verifier of the code, if one came back).
browser_login() {
  local jar="$TMP/jar-$RANDOM" challenge page action
  VERIFIER="$(rand_hex 32)"
  challenge="$(printf '%s' "$VERIFIER" | openssl dgst -sha256 -binary | b64url)"
  curl -s -o "$BODY" --max-time 20 "${CURL_EDGE[@]}" -c "$jar" -b "$jar" -G \
    --data-urlencode client_id=dlap-ui --data-urlencode response_type=code --data-urlencode scope=openid \
    --data-urlencode "redirect_uri=$APP_ORIGIN/" --data-urlencode "code_challenge=$challenge" \
    --data-urlencode code_challenge_method=S256 --data-urlencode state=verify --data-urlencode nonce=verify \
    "$ID_ORIGIN/realms/dlap/protocol/openid-connect/auth"
  page="$(cat "$BODY")"
  action="$(printf '%s' "$page" | grep -o 'action="[^"]*"' | head -n 1 | sed -e 's/^action="//' -e 's/"$//' -e 's/&amp;/\&/g')"
  LOGIN_STATUS=000
  LOGIN_LOCATION=""
  [ -n "$action" ] || return 1
  read -r LOGIN_STATUS LOGIN_LOCATION < <(curl -s -o "$BODY" --max-time 20 "${CURL_EDGE[@]}" -c "$jar" -b "$jar" \
    -w '%{http_code} %{redirect_url}\n' --data-urlencode "username=$1" --data-urlencode "password=$2" "$action")
  return 0
}

# purge_objects <name prefix>: delete the clients, users and key components whose name starts with
# the prefix. Found by name, not remembered, because they are created in command substitutions.
# Prints how many objects with that prefix are left.
purge_objects() {
  local prefix="$1" id left
  for id in $(kc_json "/clients?clientId=$prefix&search=true" | pyj "' '.join(c['id'] for c in d if c['clientId'].startswith('$prefix'))"); do
    kc DELETE "/clients/$id" >/dev/null
  done
  for id in $(kc_json "/users?search=$prefix&max=1000&briefRepresentation=true" | pyj "' '.join(u['id'] for u in d if u['username'].startswith('$prefix'))"); do
    kc DELETE "/users/$id" >/dev/null
  done
  for id in $(kc_json "/components?type=org.keycloak.keys.KeyProvider" | pyj "' '.join(c['id'] for c in d if c['name'].startswith('$prefix'))"); do
    kc DELETE "/components/$id" >/dev/null
  done
  left=$(( $(kc_json "/clients?clientId=$prefix&search=true" | pyj "len([c for c in d if c['clientId'].startswith('$prefix')])") \
         + $(kc_json "/users?search=$prefix&max=1000&briefRepresentation=true" | pyj "len([u for u in d if u['username'].startswith('$prefix')])") \
         + $(kc_json "/components?type=org.keycloak.keys.KeyProvider" | pyj "len([c for c in d if c['name'].startswith('$prefix')])") ))
  printf '%s' "$left"
}

cleanup() {
  local left
  left="$(purge_objects "$PREFIX-" 2>/dev/null || echo "?")"
  if [ "$left" != 0 ]; then
    echo "verify-issuer.sh: WARN: $left test object(s) named $PREFIX-* are still in the realm; remove them with --purge" >&2
  fi
  rm -rf "$TMP"
}

# --- K: live Keycloak -------------------------------------------------------------------------------

keycloak_checks() {
  local version want status realm ui code loc claims id

  if selected K-01; then
    want="$(demo_dc config --format json 2>/dev/null | pyj "d['services']['keycloak']['image'].split(':')[1].split('@')[0]")"
    version="$(curl -s --max-time 15 -H "Authorization: Bearer $(admin_token)" "$KC_LOCAL/admin/serverinfo" | pyj "d['systemInfo']['version']")"
    check K-01 "the running Keycloak is the pinned 26.x release" "$version" "$want"
  fi

  if selected K-02; then
    status="$(edge "$ID_ORIGIN/realms/dlap/.well-known/openid-configuration")"
    check K-02 "the public discovery document (through Caddy) names the public issuer" "$status $(pyj "d['issuer']" <"$BODY")" "200 $ISSUER"
  fi

  if selected K-03; then
    realm="$(kc_json "")"
    check K-03 "as imported: no self-registration, no reset password, no remember-me, brute force on, the demo browser flow, no identity provider" \
      "$(printf '%s' "$realm" | pyj "[d['registrationAllowed'], d['resetPasswordAllowed'], d['rememberMe'], d['bruteForceProtected'], d['browserFlow']]") $(kc_json /identity-provider/instances)" \
      '[false, false, false, true, "dlap demo browser"] []'
  fi

  if selected K-04; then
    ui="$(kc_json "/clients?clientId=dlap-ui" | pyj "d[0]")"
    check K-04 "dlap-ui as imported: no implicit flow, no password grant, PKCE S256, exact redirect URIs" \
      "$(printf '%s' "$ui" | pyj "[d['implicitFlowEnabled'], d['directAccessGrantsEnabled'], d['attributes'].get('pkce.code.challenge.method'), sorted(d['redirectUris']), d['webOrigins']]")" \
      "[false, false, \"S256\", [\"$APP_ORIGIN/\", \"$APP_ORIGIN/silent-check-sso.html\"], [\"$APP_ORIGIN\"]]"
  fi

  if selected K-05; then
    loc="$(curl -s -o /dev/null -w '%{redirect_url}' --max-time 15 "${CURL_EDGE[@]}" -G --data-urlencode client_id=dlap-ui \
      --data-urlencode response_type=token --data-urlencode "redirect_uri=$APP_ORIGIN/" --data-urlencode nonce=n \
      "$ID_ORIGIN/realms/dlap/protocol/openid-connect/auth")"
    check K-05 "an implicit-flow request is refused" "$([[ "$loc" == *"error=unauthorized_client"* ]] && echo refused || echo "$loc")" refused
  fi

  if selected K-06; then
    status="$(edge -G --data-urlencode client_id=dlap-ui --data-urlencode response_type=code \
      --data-urlencode "redirect_uri=$APP_ORIGIN/evil" --data-urlencode code_challenge=abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG \
      --data-urlencode code_challenge_method=S256 "$ID_ORIGIN/realms/dlap/protocol/openid-connect/auth")"
    check K-06 "a redirect URI off the exact list is refused" "$status" 400
  fi

  if selected K-07; then
    loc="$(curl -s -o /dev/null -w '%{redirect_url}' --max-time 15 "${CURL_EDGE[@]}" -G --data-urlencode client_id=dlap-ui \
      --data-urlencode response_type=code --data-urlencode "redirect_uri=$APP_ORIGIN/" "$ID_ORIGIN/realms/dlap/protocol/openid-connect/auth")"
    check K-07 "a code request without PKCE is refused" "$([[ "$loc" == *"error=invalid_request"* ]] && echo refused || echo "$loc")" refused
  fi

  if selected K-08; then
    local ui_err be_err
    ui_err="$(curl -s --max-time 15 "${CURL_EDGE[@]}" -d grant_type=password -d client_id=dlap-ui -d username=nobody -d password=nothing-at-all \
      "$ID_ORIGIN/realms/dlap/protocol/openid-connect/token" | pyj "d.get('error')")"
    be_err="$(curl -s --max-time 15 "${CURL_EDGE[@]}" -d grant_type=password -d client_id=dlap-backend \
      --data-urlencode "client_secret=$(demo_cfg DLAP_BACKEND_CLIENT_SECRET "")" -d username=nobody -d password=nothing-at-all \
      "$ID_ORIGIN/realms/dlap/protocol/openid-connect/token" | pyj "d.get('error')")"
    check K-08 "the password grant is refused for dlap-ui and dlap-backend" "$ui_err $be_err" "unauthorized_client unauthorized_client"
  fi

  local pw="Vi-$(rand_hex 12)"
  if selected K-09; then
    make_user "$PREFIX-admin" "$pw" ROLE_ADMIN >/dev/null || { fail K-09 "a ROLE_ADMIN sign-in stops at the OTP setup" "cannot create the test user"; }
    browser_login "$PREFIX-admin" "$pw"
    check K-09 "a ROLE_ADMIN sign-in without an OTP device stops at the OTP setup, with no code" \
      "$([[ "$LOGIN_LOCATION" == *"execution=CONFIGURE_TOTP"* && "$LOGIN_LOCATION" != *"code="* ]] && echo otp-setup || echo "$LOGIN_STATUS $LOGIN_LOCATION")" otp-setup
  fi

  if selected K-10; then
    make_user "$PREFIX-viewer" "$pw" ROLE_VIEWER >/dev/null || { fail K-10 "a viewer signs in" "cannot create the test user"; }
    browser_login "$PREFIX-viewer" "$pw"
    code="$(printf '%s' "$LOGIN_LOCATION" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')"
    claims=""
    if [ -n "$code" ]; then
      claims="$(jwt_claims "$(curl -s --max-time 15 "${CURL_EDGE[@]}" -d grant_type=authorization_code -d client_id=dlap-ui \
        --data-urlencode "code=$code" --data-urlencode "redirect_uri=$APP_ORIGIN/" --data-urlencode "code_verifier=$VERIFIER" \
        "$ID_ORIGIN/realms/dlap/protocol/openid-connect/token" | pyj "d['access_token']")")"
    fi
    check K-10 "a non-admin signs in without an OTP; the token has the public issuer and the top-level roles claim" \
      "$(printf '%s' "${claims:-{\}}" | pyj "[d.get('iss'), 'ROLE_VIEWER' in d.get('roles', []), 'ROLE_ADMIN' in d.get('roles', [])]")" \
      "[\"$ISSUER\", true, false]"
  fi

  if selected K-11; then
    id="$(make_user "$PREFIX-lockout" "$pw")" || { fail K-11 "brute force" "cannot create the test user"; }
    local i
    for i in 1 2 3 4 5 6; do browser_login "$PREFIX-lockout" "wrong-$i-$(rand_hex 4)"; done
    browser_login "$PREFIX-lockout" "$pw"
    check K-11 "six wrong passwords lock the account: the right one then gets no code" \
      "$(kc_json "/attack-detection/brute-force/users/$id" | pyj "d.get('disabled')") $([[ "$LOGIN_LOCATION" == *"code="* ]] && echo code || echo no-code)" \
      "true no-code"
  fi

  if selected K-12; then
    local seeded="" name
    for name in admin cdo director dataengineer datascientist; do
      [ "$(kc_json "/users?username=$name&exact=true" | pyj "len(d)")" = 0 ] || seeded="$seeded $name"
    done
    check K-12 "none of the quickstart's seeded users exists" "${seeded:-none}" none
    echo "       info: $(kc_json "/users?max=500&briefRepresentation=true" | pyj "len([u for u in d if not u['username'].startswith(('service-account-', 'verify-issuer-'))])") human account(s) in the realm"
  fi

  if selected K-13; then
    id="$(kc_json "/clients?clientId=dlap-backend" | pyj "d[0]['id']")"
    id="$(kc_json "/clients/$id/service-account-user" | pyj "d['id']")"
    check K-13 "the service account of dlap-backend holds ROLE_SERVICE_ACCOUNT and no other DLAP role" \
      "$(kc_json "/users/$id/role-mappings/realm/composite" | pyj "sorted(r['name'] for r in d if r['name'].startswith('ROLE_'))")" '["ROLE_SERVICE_ACCOUNT"]'
  fi
}

# --- I: issuer --------------------------------------------------------------------------------------

# One read endpoint per service: a GET that ROLE_ADMIN may call and that needs no data. da-logingest
# serves no business GET, so its read endpoint is /actuator/info, which also needs a token; it listens
# on 8090, the others on 8080.
ENDPOINTS=(
  "da-catalog /api/v1/catalog/assets?size=1"
  "da-policyengine /api/v1/policyengine/policies?size=1"
  "da-discovery /api/v1/discovery/statistics"
  "da-logingest:8090 /actuator/info"
  "da-reporting /api/v1/reporting/definitions?size=1"
  "da-autolabel /api/v1/label-agent/kpis"
  "da-autoarchival /api/v1/archival/overview"
  "da-autodelete /api/v1/delete-agent/dashboard/kpis"
  "da-autocompliance /api/v1/compliance/reports"
  "da-admin-service /api/v1/admin/job-statuses"
)

running_endpoints() {
  local running entry name
  running="$(demo_dc ps --services --status running 2>/dev/null | tr -d '\r')"
  for entry in "${ENDPOINTS[@]}"; do
    name="${entry%% *}"
    grep -qx "${name%%:*}" <<<"$running" && printf '%s\n' "$entry"
  done
}

# sign_jwt <kid> <key file> <issuer> [audience]: an RS256 token for ROLE_ADMIN, valid for two minutes. The
# audience is dlap-api unless the fourth argument names another one.
sign_jwt() {
  local now header payload signature
  now="$(date +%s)"
  header="$(printf '{"alg":"RS256","typ":"JWT","kid":"%s"}' "$1" | b64url)"
  payload="$(printf '{"iss":"%s","aud":["%s"],"sub":"%s","azp":"dlap-ui","typ":"Bearer","iat":%s,"exp":%s,"preferred_username":"%s","roles":["ROLE_ADMIN"],"realm_access":{"roles":["ROLE_ADMIN"]}}' \
    "$3" "${4:-dlap-api}" "$PREFIX" "$now" "$((now + 120))" "$PREFIX" | b64url)"
  signature="$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$2" -binary | b64url)"
  printf '%s.%s.%s' "$header" "$payload" "$signature"
}

issuer_checks() {
  local secret public internal master entry svc path s1 s2 s3 tries key kid realm_id body control foreign other_aud no_aud_secret no_aud
  local -a endpoints
  mapfile -t endpoints < <(running_endpoints)
  [ "${#endpoints[@]}" -gt 0 ] || { fail I-00 "the services are running" "no da-* service of the demo is running"; return; }

  secret="$(make_client "$PREFIX-admin" ROLE_ADMIN)" || { fail I-00 "the test client exists" "cannot create $PREFIX-admin"; return; }
  public="$(client_token "$ID_ORIGIN" "$PREFIX-admin" "$secret")"
  internal="$(client_token "$KC_LOCAL" "$PREFIX-admin" "$secret")"
  ADMIN_CLIENT_TOKEN="$public"

  if selected I-01; then
    check I-01 "tokens from the public and from the internal token endpoint both carry the public issuer" \
      "$(jwt_claims "$public" | pyj "d['iss']") $(jwt_claims "$internal" | pyj "d['iss']")" "$ISSUER $ISSUER"
  fi

  if selected I-02; then
    for entry in "${endpoints[@]}"; do
      svc="${entry%% *}"; path="${entry#* }"
      check "I-02" "$svc: GET $path with a token of the public issuer answers 200" "$(svc_status "$svc" "$path" "$public")" 200
    done
  fi

  if selected I-03; then
    master="$(admin_token)"
    for entry in "${endpoints[@]}"; do
      svc="${entry%% *}"; path="${entry#* }"
      check "I-03" "$svc: the same GET with a token of another issuer (master realm, other keys) answers 401" "$(svc_status "$svc" "$path" "$master")" 401
    done
  fi

  if selected I-08; then
    # Q-913: a client whose tokens carry no dlap-api audience (it has no audience mapper) gets 401 although
    # its token is signed by the realm, has the public issuer and holds ROLE_ADMIN.
    no_aud_secret="$(CLIENT_AUDIENCE= make_client "$PREFIX-noaud" ROLE_ADMIN)" || { fail I-08 "the test client exists" "cannot create $PREFIX-noaud"; return; }
    no_aud="$(client_token "$ID_ORIGIN" "$PREFIX-noaud" "$no_aud_secret")"
    check I-08a "the token of a client without the audience mapper has no dlap-api audience"       "$(jwt_claims "$no_aud" | pyj "'dlap-api' in (d.get('aud') if isinstance(d.get('aud'), list) else [d.get('aud')])")" false
    for entry in "${endpoints[@]}"; do
      svc="${entry%% *}"; path="${entry#* }"
      check "I-08" "$svc: the same GET with a realm token that lacks the audience dlap-api answers 401" "$(svc_status "$svc" "$path" "$no_aud")" 401
    done
  fi

  if selected I-04 || selected I-05 || selected I-09; then
    # A passive key in the realm: published in the key set, never used by Keycloak to sign. A token
    # signed with it passes every signature check, so the issuer is the only thing that differs.
    key="$TMP/verify-key.pem"
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$key" 2>/dev/null || { fail I-04 "a test key" "openssl genpkey failed"; return; }
    realm_id="$(kc_json "" | pyj "d['id']")"
    body="$("$PYTHON" -c '
import json, sys
print(json.dumps({"name": sys.argv[1], "providerId": "rsa", "providerType": "org.keycloak.keys.KeyProvider", "parentId": sys.argv[2],
                  "config": {"priority": ["-100"], "enabled": ["true"], "active": ["false"], "algorithm": ["RS256"],
                             "privateKey": [open(sys.argv[3], encoding="ascii").read()]}}))' "$PREFIX-key" "$realm_id" "$(demo_native_path "$key")")"
    kc POST /components "$body" >/dev/null
    CREATED_COMPONENT="$(kc_json "/components?name=$PREFIX-key" | pyj "d[0]['id']")"
    kid="$(kc_json /keys | pyj "[k['kid'] for k in d['keys'] if k.get('providerId') == '$CREATED_COMPONENT'][0]")"
    if [ -z "$CREATED_COMPONENT" ] || [ -z "$kid" ]; then fail I-04 "a test key in the realm" "the key component was not created"; return; fi
    if ! curl -s --max-time 15 "$KC_LOCAL/realms/dlap/protocol/openid-connect/certs" | grep -q "\"$kid\""; then
      fail I-04 "the test key is published" "kid $kid is not in the realm's key set"; return
    fi
    control="$(sign_jwt "$kid" "$key" "$ISSUER")"
    foreign="$(sign_jwt "$kid" "$key" "https://issuer.invalid/realms/dlap")"
    other_aud="$(sign_jwt "$kid" "$key" "$ISSUER" "some-other-api")"
    for entry in "${endpoints[@]}"; do
      svc="${entry%% *}"; path="${entry#* }"
      # The services cache the key set; an unknown key id makes them fetch it again, at most so often.
      tries=0
      while :; do
        s1="$(svc_status "$svc" "$path" "$control")"
        [ "$s1" = 200 ] || [ "$tries" -ge 12 ] && break
        tries=$((tries + 1)); sleep 5
      done
      s2="$(svc_status "$svc" "$path" "$foreign")"
      s3="$(svc_status "$svc" "$path" "$other_aud")"
      selected I-04 && check I-04 "$svc: a token signed with a published realm key and the public issuer answers 200 (control)" "$s1" 200
      selected I-05 && check I-05 "$svc: the same token with another issuer answers 401" "$s2" 401
      selected I-09 && check I-09 "$svc: the same token with the public issuer and another audience answers 401" "$s3" 401
    done
    kc DELETE "/components/$CREATED_COMPONENT" >/dev/null && CREATED_COMPONENT=""
  fi

  if selected I-07; then
    # What the services themselves use for service-to-service calls (ADR-0034).
    check I-07 "a dlap-backend token from the internal token endpoint (the services' own) is accepted: da-catalog answers 200"       "$(svc_status da-catalog "/api/v1/catalog/assets?size=1" "$(client_token "$KC_LOCAL" dlap-backend "$(demo_cfg DLAP_BACKEND_CLIENT_SECRET "")")")" 200
  fi

  if selected I-06; then
    check I-06 "through the public front door (Caddy, gateway): GET /api/v1/catalog/assets answers 200 with the public token, 401 without one" \
      "$(edge -H "Authorization: Bearer $public" "$APP_ORIGIN/api/v1/catalog/assets?size=1") $(edge "$APP_ORIGIN/api/v1/catalog/assets?size=1")" "200 401"
  fi
}

# --- A: AuthZEN ---------------------------------------------------------------------------------------

authzen_checks() {
  local request secret prospect admin_status
  request='{"subject":{"type":"user","id":"verify-issuer"},"action":{"name":"read"},"resource":{"type":"asset","id":"verify-issuer"}}'
  secret="$(make_client "$PREFIX-prospect" ROLE_CDO ROLE_DIRECTOR ROLE_DATA_ENGINEER ROLE_DATA_SCIENTIST ROLE_DATA_ANALYST \
    ROLE_POLICY_MANAGER ROLE_AUDITOR ROLE_VIEWER)" || { fail A-00 "the test client exists" "cannot create $PREFIX-prospect"; return; }
  prospect="$(client_token "$ID_ORIGIN" "$PREFIX-prospect" "$secret")"
  if [ -z "${ADMIN_CLIENT_TOKEN:-}" ]; then
    secret="$(make_client "$PREFIX-admin" ROLE_ADMIN)" || { fail A-00 "the test client exists" "cannot create $PREFIX-admin"; return; }
    ADMIN_CLIENT_TOKEN="$(client_token "$ID_ORIGIN" "$PREFIX-admin" "$secret")"
  fi
  selected A-01 && check A-01 "POST /access/v1/evaluation without a token answers 401" \
    "$(edge -H 'Content-Type: application/json' --data-binary "$request" "$APP_ORIGIN/access/v1/evaluation")" 401
  sleep 1
  selected A-02 && check A-02 "a token with every human role except ROLE_ADMIN answers 403" \
    "$(edge -H 'Content-Type: application/json' -H "Authorization: Bearer $prospect" --data-binary "$request" "$APP_ORIGIN/access/v1/evaluation")" 403
  sleep 1
  if selected A-03; then
    admin_status="$(edge -H 'Content-Type: application/json' -H "Authorization: Bearer $ADMIN_CLIENT_TOKEN" --data-binary "$request" "$APP_ORIGIN/access/v1/evaluation")"
    check A-03 "a ROLE_ADMIN token gets a verdict (200 with a decision)" "$admin_status $(pyj "'decision' in d" <"$BODY")" "200 true"
  fi
}

# --- Run ----------------------------------------------------------------------------------------------

if [ -z "$ONLY" ] || [[ "$ONLY" == S* ]]; then static_checks; fi
if [ "$STATIC" = 0 ] && { [ -z "$ONLY" ] || [[ "$ONLY" == K* || "$ONLY" == I* || "$ONLY" == A* ]]; }; then
  trap cleanup EXIT
  trap 'exit 130' INT TERM
  [ -n "$(admin_token)" ] || { echo "verify-issuer.sh: no admin token from $KC_LOCAL (is the stack up, are KEYCLOAK_ADMIN and KEYCLOAK_ADMIN_PASSWORD right?)" >&2; exit 2; }
  edge_tls || { echo "verify-issuer.sh: cannot read Caddy's local CA (DEMO_TLS=internal)" >&2; exit 2; }
  leftover="$(kc_json "/clients?clientId=verify-issuer-&search=true" | pyj "len([c for c in d if c['clientId'].startswith('verify-issuer-')])")"
  if [ "$PURGE" = 1 ]; then
    echo "verify-issuer.sh: --purge: $(purge_objects verify-issuer-) test object(s) left after the purge"
  elif [ "${leftover:-0}" != 0 ]; then
    echo "verify-issuer.sh: WARN: $leftover test client(s) of an earlier run are in the realm; --purge removes them" >&2
  fi
  if [ -z "$ONLY" ] || [[ "$ONLY" == K* ]]; then keycloak_checks; fi
  if [ -z "$ONLY" ] || [[ "$ONLY" == I* ]]; then issuer_checks; fi
  if [ -z "$ONLY" ] || [[ "$ONLY" == A* ]]; then authzen_checks; fi
else
  trap 'rm -rf "$TMP"' EXIT
fi

echo
echo "verify-issuer.sh: $PASS passed, $FAIL failed${FAILED[*]:+ (${FAILED[*]})}"
[ "$FAIL" -eq 0 ]
