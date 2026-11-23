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
# Named tests of the demo overlay (DEMO-01). Every acceptance line of the package maps to tests here
# (README.md, "Tests"): a test has an id, a one-line claim and a pass or fail, so "verified" always
# means a test that can fail.
#
#   V-*   verify.sh passes on the real files, and fails on each of 26 deliberate breakages (mutations)
#   AUD-* the audience check (Q-913): keycloak/verify-issuer.sh --static passes on the real files and fails
#         on each of 4 deliberate breakages (no audience mapper, a waiver, the check off, another audience)
#   ENV-* the launcher refuses weak or missing configuration (ENV-06 and ENV-12 run it in a copy of the
#         folder without keycloak/, the state before DEMO-02)
#   N-*   the real nginx.demo.conf, run in a hardened container in front of stub upstreams
#   C-*   the real Caddyfile, run in a hardened container in front of stub gateway and Keycloak
#   R-*   nightly-reset.sh: --check, --dry-run, golden checksum
#   F-*   files: shell syntax, licence headers, no dash characters, git ignore rules
#
# Usage:  test-demo.sh [--no-docker] [--only PREFIX]
#   --no-docker   skip N-* and C-* (they start containers with docker run on throwaway networks;
#                 they never use docker compose and never touch the demo stack)
#   --only PREFIX run the tests whose id starts with PREFIX (V, AUD, ENV, N, C, R, F)
# Needs: docker (compose 2.24+, and the images nginx:1.27-alpine and the pinned caddy for N and C),
# python3, curl.
# Exit:  0 all passed; 1 a test failed.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEMO_DIR="$SCRIPT_DIR"
REPO_ROOT="$(cd "$DEMO_DIR/../../.." && pwd)"
export MSYS_NO_PATHCONV=1

DOCKER=1
ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --no-docker) DOCKER=0; shift ;;
    --only) ONLY="${2:-}"; shift 2 ;;
    -h|--help) sed -n '/^# Named tests/,/^# Exit/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

TMP="$(mktemp -d)"
PASS=0
FAIL=0
FAILED=()
CONTAINERS=()
NETWORKS=()

native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# Remove the containers, networks and volumes a suite started. Each suite does this when it ends, so
# the next one starts clean: the stubs of both suites share container names.
release() {
  local c n
  for c in "${CONTAINERS[@]+"${CONTAINERS[@]}"}"; do docker rm -f -v "$c" >/dev/null 2>&1 || true; done
  for n in "${NETWORKS[@]+"${NETWORKS[@]}"}"; do docker network rm "$n" >/dev/null 2>&1 || true; done
  CONTAINERS=()
  NETWORKS=()
  docker volume ls -q --filter name=demo01t- 2>/dev/null | tr -d '\r' | xargs -r docker volume rm >/dev/null 2>&1 || true
}
cleanup() {
  release
  rm -rf "$TMP"
}
trap cleanup EXIT

selected() { [ -z "$ONLY" ] || [[ "$1" == "$ONLY"* ]]; }
# A block runs when --only names it or one of its tests.
block() { [ -z "$ONLY" ] || [[ "$ONLY" == "$1"* ]] || [[ "$1" == "$ONLY"* ]]; }

# t_ok <id> <claim>   /   t_no <id> <claim> <detail>
t_ok() { PASS=$((PASS + 1)); printf 'PASS %-8s %s\n' "$1" "$2"; }
t_no() { FAIL=$((FAIL + 1)); FAILED+=("$1"); printf 'FAIL %-8s %s\n    %s\n' "$1" "$2" "${3:-}"; }

# expect <id> <claim> <condition...>: run the condition (a command), pass when it succeeds.
expect() {
  local id="$1" claim="$2"; shift 2
  selected "$id" || return 0
  if "$@" >"$TMP/last.out" 2>&1; then t_ok "$id" "$claim"; else t_no "$id" "$claim" "$(head -c 600 "$TMP/last.out" | tr '\n' ' ')"; fi
}

# ---------------------------------------------------------------------------------------------------
# V: verify.sh on the real files and on mutations
# ---------------------------------------------------------------------------------------------------

mutate() { # mutate <service|-> <old> <new> <out file> : replace the first <old> inside <service>'s block
  python - "$(native "$DEMO_DIR/docker-compose.demo.yml")" "$1" "$2" "$3" "$(native "$4")" <<'PY'
import re, sys
src, service, old, new, out = sys.argv[1:6]
text = open(src, encoding="utf-8").read()
if service == "-":
    lo, hi = 0, len(text)
else:
    m = re.search(rf"^  {re.escape(service)}:\n", text, re.M)
    if not m:
        sys.exit("no service " + service)
    lo = m.end()
    n = re.search(r"^  [a-z0-9-]+:\n|^[a-z]", text[lo:], re.M)
    hi = lo + (n.start() if n else len(text) - lo)
block = text[lo:hi]
if old not in block:
    sys.exit(f"{old!r} not found in {service}")
block = block.replace(old, new, 1)
open(out, "w", encoding="utf-8", newline="\n").write(text[:lo] + block + text[hi:])
PY
}

verify_fails() { # verify_fails <mutated overlay> <expected check id> [env assignments...]
  local overlay="$1" id="$2"; shift 2
  local out rc
  out="$(env DEMO_OVERLAY_FILE="$(native "$overlay")" "$@" bash "$DEMO_DIR/verify.sh" 2>&1)"; rc=$?
  [ "$rc" -eq 1 ] && grep -q "$id:" <<<"$out" || { echo "verify.sh exit $rc, wanted 1 with $id: $out"; return 1; }
}

if block V; then
  expect V-00 "verify.sh passes on the real overlay, nginx and Caddy files" bash "$DEMO_DIR/verify.sh"

  mut() { # mut <test id> <claim> <check id> <service> <old> <new>
    local id="$1" claim="$2" check="$3" service="$4" old="$5" new="$6" f="$TMP/mut-$1.yml"
    selected "$id" || return 0
    if mutate "$service" "$old" "$new" "$f" >"$TMP/mut.err" 2>&1; then
      expect "$id" "$claim" verify_fails "$f" "$check"
    else
      t_no "$id" "$claim" "mutation failed: $(cat "$TMP/mut.err")"
    fi
  }
  mut V-01 "fails when postgres is published"                     V03 postgresql 'ports: !reset []' 'ports: ["5432:5432"]'
  mut V-02 "fails when a JVM loses its memory limit"              V04 da-catalog 'memory: 512m' 'cpus: "1.0"'
  mut V-03 "fails when a JVM loses -Xmx"                          V05 da-reporting '-Xmx224m' '-Dno.heap=1'
  mut V-04 "fails when the GCP log source is switched on"         V08 da-logingest 'DLAP_LOGINGEST_GCP_ENABLED=false' 'DLAP_LOGINGEST_GCP_ENABLED=true'
  mut V-05 "fails when the fixture replay is on by default"       V08 da-logingest 'DEMO_REPLAY_ENABLED:-false' 'DEMO_REPLAY_ENABLED:-true'
  mut V-06 "fails when deletes are not a dry run"                 V08 da-autodelete 'DLAP_AUTODELETE_DRY_RUN_CLOUD_DELETE=true' 'DLAP_AUTODELETE_DRY_RUN_CLOUD_DELETE=false'
  mut V-07 "fails when autolabel falls back to the mock"          V07 da-autolabel 'dlap.autolabel.llm-provider":"rules' 'dlap.autolabel.llm-provider":"mock'
  mut V-08 "fails when postgres logs every statement"             V10 postgresql 'log_statement=none' 'log_statement=all'
  mut V-09 "fails when ollama is part of the default profile"     V02 ollama 'profiles: ["not-in-demo"]' 'profiles: []'
  mut V-10 "fails when Keycloak is published beyond the loopback" V03 keycloak '"127.0.0.1:${DLAP_PORT_KEYCLOAK:-8180}:8080"' '"${DLAP_PORT_KEYCLOAK:-8180}:8080"'
  mut V-11 "fails when the service network has a route out"       V09 - 'internal: true' 'internal: false'
  mut V-12 "fails when a wave-2 service stops waiting for wave 1" V14 da-reporting $'      da-discovery:\n        condition: service_healthy\n' ''
  mut V-13 "fails when the gateway root file system is writable"  V12 gateway 'read_only: true' 'read_only: false'
  mut V-14 "fails when the Caddy image is not pinned by digest"   V13 caddy '@sha256:d44355d3c2149dc580ce2cac735955d1c08d3d00882c30489c241aa51a5c10d9' ''
  mut V-15 "fails when Caddy publishes another port"              V03 caddy '- "${DEMO_BIND_ADDR:-0.0.0.0}:${DEMO_HTTPS_PORT:-443}:443"' $'- "${DEMO_BIND_ADDR:-0.0.0.0}:${DEMO_HTTPS_PORT:-443}:443"\n      - "0.0.0.0:8443:8443"'
  mut V-16 "fails when a service leaves the prod profile"         V06 da-catalog 'SPRING_PROFILES_ACTIVE=prod' 'SPRING_PROFILES_ACTIVE=dev'
  mut V-17 "fails when Kafka gets a big heap"                     V11 kafka '-Xmx256m' '-Xmx1g'
  mut V-18 "fails when the limits exceed the budget"              V04 da-discovery 'memory: 640m' 'memory: 4096m'
  mut V-19 "fails when Hibernate may change the schema"           V06 da-policyengine 'SPRING_JPA_HIBERNATE_DDL_AUTO=validate' 'SPRING_JPA_HIBERNATE_DDL_AUTO=update'
  mut V-20 "fails when a JVM does not exit on out of memory"      V05 da-autoarchival '-XX:+ExitOnOutOfMemoryError' '-XX:+UseSerialGC'
  mut V-21 "fails when Azure archival is not mocked"              V08 da-autoarchival 'DLAP_AUTOARCHIVAL_AZURE_MOCK_MODE=true' 'DLAP_AUTOARCHIVAL_AZURE_MOCK_MODE=false'
  mut V-26 "fails when the public host would serve the app over plain HTTP" V03 caddy 'DEMO_APP_HTTP_ADDRESS:-:65080}' 'DEMO_APP_HTTP_ADDRESS:-http://demo.example}'
  mut V-22 "fails when a service joins the edge network"          V09 da-catalog 'ports: !reset []' $'ports: !reset []\n    networks:\n      - dlap-network\n      - demo-edge'

  # Mutations of the nginx and Caddy files: verify.sh reads them from DEMO_NGINX_CONF / DEMO_CADDYFILE.
  file_mut() { # file_mut <id> <claim> <check> <source file> <var> <python replace expr...>
    local id="$1" claim="$2" check="$3" src="$4" var="$5" old="$6" new="$7" f="$TMP/mut-$1.txt" out rc
    selected "$id" || return 0
    python - "$(native "$src")" "$(native "$f")" "$old" "$new" <<'PY'
import sys
src, out, old, new = sys.argv[1:5]
text = open(src, encoding="utf-8").read()
if old not in text:
    sys.exit("not found: " + old)
open(out, "w", encoding="utf-8", newline="\n").write(text.replace(old, new))
PY
    out="$(env "$var=$(native "$f")" bash "$DEMO_DIR/verify.sh" 2>&1)"; rc=$?
    if [ "$rc" -eq 1 ] && grep -q "$check:" <<<"$out"; then t_ok "$id" "$claim"; else t_no "$id" "$claim" "exit $rc: $out"; fi
  }
  file_mut V-23 "fails when nginx loses the test-rule block"           V15 "$DEMO_DIR/gateway/nginx.demo.conf" DEMO_NGINX_CONF 'location ~* (^|/)test-rule(/|$)' 'location ~* (^|/)zzz(/|$)'
  file_mut V-24 "fails when nginx proxies to a literal upstream name"  V15 "$DEMO_DIR/gateway/nginx.demo.conf" DEMO_NGINX_CONF 'location /api/v1/catalog/       { limit_req zone=demo_api burst=60 nodelay; proxy_pass $up_catalog; }' 'location /api/v1/catalog/       { proxy_pass http://da-catalog:8080; }'
  file_mut V-25 "fails when the Caddyfile loses the master realm block" V15 "$DEMO_DIR/caddy/Caddyfile" DEMO_CADDYFILE '|^/+realms/+master(/|$)' ''
fi

# ---------------------------------------------------------------------------------------------------
# AUD: the services require the audience dlap-api and the realm writes it (Q-913, ID-04)
# ---------------------------------------------------------------------------------------------------

# verify-issuer.sh --static on the real files, or on a mutated realm file (DEMO_KEYCLOAK_REALM_FILE) or with
# an extra compose file (DEMO_EXTRA_COMPOSE). Prints the output; the exit status is the script's.
issuer_static() { env "$@" bash "$DEMO_DIR/keycloak/verify-issuer.sh" --static 2>&1; }
issuer_static_fails() { # issuer_static_fails <check id> <env assignment>...
  local id="$1" out rc; shift
  out="$(issuer_static "$@")"; rc=$?
  [ "$rc" -eq 1 ] && grep -q "^FAIL $id " <<<"$out" || { echo "verify-issuer.sh --static exit $rc, wanted 1 with FAIL $id: $out"; return 1; }
}

if block AUD; then
  expect AUD-00 "verify-issuer.sh --static passes on the real realm and overlays (S-13 among them)" bash -c '
      out="$(bash "$1/keycloak/verify-issuer.sh" --static 2>&1)" || { echo "$out"; exit 1; }
      grep -q "^PASS S-13 " <<<"$out"' _ "$DEMO_DIR"

  if selected AUD-01; then
    python - "$(native "$DEMO_DIR/keycloak/dlap-demo-realm.json")" "$(native "$TMP/realm-no-aud.json")" <<'PY'
import json, sys
realm = json.load(open(sys.argv[1], encoding="utf-8"))
for client in realm["clients"]:
    if client["clientId"] == "dlap-backend":
        client["protocolMappers"] = [m for m in client["protocolMappers"] if m["protocolMapper"] != "oidc-audience-mapper"]
json.dump(realm, open(sys.argv[2], "w", encoding="utf-8"), indent=2)
PY
    expect AUD-01 "fails when dlap-backend loses the audience mapper in the realm file" issuer_static_fails S-13 "DEMO_KEYCLOAK_REALM_FILE=$(native "$TMP/realm-no-aud.json")"
  fi

  extra_compose() { # extra_compose <file> <service> <variable> <value> : a compose file that sets one variable
    printf "services:\n  %s:\n    environment:\n      %s: %s\n" "$2" "$3" "\"$4\"" > "$1"
  }
  if selected AUD-02; then
    extra_compose "$TMP/extra-waiver.yml" da-catalog DLAP_SECURITY_DEV_SECRET_GUARD_ACCEPTED dlap.security.jwt.validate-audience
    expect AUD-02 "fails when a boot-guard waiver (DLAP_SECURITY_DEV_SECRET_GUARD_ACCEPTED) comes back" issuer_static_fails S-13 "DEMO_EXTRA_COMPOSE=$(native "$TMP/extra-waiver.yml")"
  fi
  if selected AUD-03; then
    extra_compose "$TMP/extra-off.yml" da-reporting DLAP_SECURITY_JWT_VALIDATE_AUDIENCE false
    expect AUD-03 "fails when one service switches audience validation off" issuer_static_fails S-13 "DEMO_EXTRA_COMPOSE=$(native "$TMP/extra-off.yml")"
  fi
  if selected AUD-04; then
    extra_compose "$TMP/extra-other.yml" da-autolabel DLAP_SECURITY_JWT_EXPECTED_AUDIENCE some-other-api
    expect AUD-04 "fails when one service expects another audience than the realm writes" issuer_static_fails S-13 "DEMO_EXTRA_COMPOSE=$(native "$TMP/extra-other.yml")"
  fi
fi

# ---------------------------------------------------------------------------------------------------
# ENV: the launcher refuses weak or missing configuration
# ---------------------------------------------------------------------------------------------------

env_file() { # env_file <name> <key=value>... : write a complete good file, then apply the overrides
  local f="$TMP/env-$1" kv key
  shift
  cat > "$f" <<'EOF'
DEMO_APP_HOST=demo.example.org
DEMO_ID_HOST=id.example.org
POSTGRES_PASSWORD=pg-0123456789abcdef
KEYCLOAK_ADMIN_PASSWORD=kc-0123456789abcdef
DLAP_BACKEND_CLIENT_SECRET=bk-0123456789abcdef0123456789abcdef
EOF
  # The thirteen database role passwords (SEC-12), built here so that no literal looks like a credential.
  n=0
  for key in DLAP_COMMON DA_DISCOVERY DA_CATALOG DA_POLICYENGINE DA_REPORTING DA_AUTOLABEL DA_AUTOARCHIVAL DA_AUTODELETE \
             DA_AUTOCOMPLIANCE DA_ADMIN DA_LOGINGEST DLAP_MONITOR DLAP_BACKUP; do
    n=$((n + 1))
    printf '%s_DB_PASSWORD=role%02d-%s\n' "$key" "$n" "$(printf 'x%.0s' $(seq 1 20))" >> "$f"
  done
  for kv in "$@"; do
    key="${kv%%=*}"
    grep -v "^$key=" "$f" > "$f.tmp"; mv "$f.tmp" "$f"
    [ "${kv#*=}" = "__unset__" ] || echo "$kv" >> "$f"
  done
  echo "$f"
}

up_check_output() { bash "$DEMO_DIR/up.sh" --check --env-file "$1" 2>&1; }

# A copy of the demo folder without keycloak/, laid out as deployment/environments/demo under a throwaway
# repository root: the state before DEMO-02, so the dev-realm guard of up.sh can still be tested. The
# launcher finds the overlay by its path next to the script, so the real folder never loses it.
no_overlay_up() { # no_overlay_up <env file>: up.sh --check in the copy
  local root="$TMP/no-overlay" demo
  demo="$root/deployment/environments/demo"
  if [ ! -d "$demo" ]; then
    mkdir -p "$root/deployment/environments"
    cp -R "$DEMO_DIR" "$demo"
    rm -rf "$demo/keycloak" "$demo/spa" "$demo/state"
  fi
  bash "$demo/up.sh" --check --env-file "$1" 2>&1
}
no_overlay_refuses() { # no_overlay_refuses <env file> <expected text>
  local out
  out="$(no_overlay_up "$1")" && { echo "up.sh accepted the file: $out"; return 1; }
  grep -qF "$2" <<<"$out" || { echo "wanted '$2' in: $out"; return 1; }
}
no_overlay_no_realm_complaint() { # an accepted setting: no complaint about the dev realm remains
  local out
  out="$(no_overlay_up "$1")"
  if grep -q "DEMO_ALLOW_DEV_REALM" <<<"$out"; then echo "$out"; return 1; fi
  grep -q "^up.sh: the demo will not start" <<<"$out" || { echo "wanted the other problems to be listed: $out"; return 1; }
}
up_has_no_complaint_about() { # up_has_no_complaint_about <env file> <text>
  local out
  out="$(up_check_output "$1")"
  if grep -qF "$2" <<<"$out"; then echo "$out"; return 1; fi
}

up_refuses() { # up_refuses <env file> <expected text>
  local out
  out="$(up_check_output "$1")" && { echo "up.sh accepted the file: $out"; return 1; }
  grep -qF "$2" <<<"$out" || { echo "wanted '$2' in: $out"; return 1; }
}

up_other_problems() { # only the missing jars and SPA remain for a good file: no other complaint
  local out
  out="$(up_check_output "$1")"
  if grep -E '^  - ' <<<"$out" | grep -vE 'no jar for|no built SPA' | grep -q .; then echo "$out"; return 1; fi
  return 0
}

up_other_problems_both() { up_other_problems "$1" && up_other_problems "$2"; }

if block ENV; then
  expect ENV-01 "refuses a missing POSTGRES_PASSWORD"               up_refuses "$(env_file a POSTGRES_PASSWORD=__unset__)" "POSTGRES_PASSWORD is not set"
  expect ENV-02 "refuses a CHANGE_ME placeholder"                   up_refuses "$(env_file b KEYCLOAK_ADMIN_PASSWORD=CHANGE_ME_x)" "KEYCLOAK_ADMIN_PASSWORD still has the placeholder"
  expect ENV-03 "refuses the quickstart Postgres password"          up_refuses "$(env_file c POSTGRES_PASSWORD=dlap)" "POSTGRES_PASSWORD is a published quickstart default"
  expect ENV-04 "refuses the published backend client secret"       up_refuses "$(env_file d DLAP_BACKEND_CLIENT_SECRET=change-me-dev-only)" "DLAP_BACKEND_CLIENT_SECRET is a published quickstart default"
  expect ENV-05 "refuses a replay switch in the environment file"   up_refuses "$(env_file e DEMO_REPLAY_ENABLED=true)" "DEMO_REPLAY_ENABLED is set in"
  expect ENV-06 "without the Keycloak overlay, refuses the dev realm unless it is accepted" no_overlay_refuses "$(env_file f DEMO_ALLOW_DEV_REALM=0)" "DEMO_ALLOW_DEV_REALM=1"
  expect ENV-07 "refuses host names left at CHANGE_ME"              up_refuses "$(env_file g DEMO_APP_HOST=demo.CHANGE_ME.example)" "DEMO_APP_HOST is not set to a real host name"
  expect ENV-08 "refuses a forbidden domain as the demo domain"     up_refuses "$(env_file h DEMO_ID_HOST=id.shop.example DEMO_FORBIDDEN_HOSTS=other.example,shop.example)" "must not use shop.example"
  expect ENV-09 "refuses the plus profile without an encryption key" up_refuses "$(env_file i COMPOSE_PROFILES=plus)" "DLAP_ENCRYPTION_KEY is not set"
  expect ENV-10 "a complete file leaves only the build artifacts to complain about" up_other_problems "$(env_file j)"
  expect ENV-11 "a missing environment file is reported with the way to create it" up_refuses "$TMP/does-not-exist" "environment file not found"
  expect ENV-12 "without the Keycloak overlay, DEMO_ALLOW_DEV_REALM=1 is accepted" no_overlay_no_realm_complaint "$(env_file l DEMO_ALLOW_DEV_REALM=1)"
  expect ENV-13 "with the Keycloak overlay present, DEMO_ALLOW_DEV_REALM is not asked for" up_has_no_complaint_about "$(env_file m)" "DEMO_ALLOW_DEV_REALM"
  expect ENV-14 "refuses a CHANGE_ME placeholder for KEYCLOAK_DB_PASSWORD" up_refuses "$(env_file n KEYCLOAK_DB_PASSWORD=CHANGE_ME_keycloak_database)" "KEYCLOAK_DB_PASSWORD still has the placeholder"
  expect ENV-15 "refuses a published default for KEYCLOAK_DB_PASSWORD" up_refuses "$(env_file o KEYCLOAK_DB_PASSWORD=keycloak)" "KEYCLOAK_DB_PASSWORD is a published quickstart default"
  expect ENV-16 "a real KEYCLOAK_DB_PASSWORD, or none, leaves only the build artifacts to complain about" up_other_problems_both "$(env_file p KEYCLOAK_DB_PASSWORD=kdb-0123456789abcdef)" "$(env_file q)"
  expect ENV-17 "refuses the published MCP gateway client secret (Q-458)" up_refuses "$(env_file r DLAP_MCP_GATEWAY_CLIENT_SECRET=change-me-dev-only)" "DLAP_MCP_GATEWAY_CLIENT_SECRET is a published quickstart default"
  expect ENV-18 "refuses a CHANGE_ME placeholder for the MCP gateway client secret" up_refuses "$(env_file s DLAP_MCP_GATEWAY_CLIENT_SECRET=CHANGE_ME_x)" "DLAP_MCP_GATEWAY_CLIENT_SECRET still has the placeholder"
  expect ENV-19 "a real MCP gateway client secret, or none, leaves only the build artifacts to complain about" up_other_problems_both "$(env_file t DLAP_MCP_GATEWAY_CLIENT_SECRET=gw-0123456789abcdef0123456789abcdef)" "$(env_file u)"
  # SEC-12 (Q-176, Q-449, Q-455): one real password per database role, none of them a published default.
  expect ENV-20 "refuses a missing database role password" up_refuses "$(env_file v DA_CATALOG_DB_PASSWORD=__unset__)" "DA_CATALOG_DB_PASSWORD is not set"
  expect ENV-21 "refuses a CHANGE_ME placeholder for a database role password" up_refuses "$(env_file w DA_ADMIN_DB_PASSWORD=CHANGE_ME_x)" "DA_ADMIN_DB_PASSWORD still has the placeholder"
  expect ENV-22 "refuses the published default of a service role (da_catalog_pass)" up_refuses "$(env_file x DA_CATALOG_DB_PASSWORD=da_catalog_pass)" "DA_CATALOG_DB_PASSWORD is a published quickstart default"
  expect ENV-23 "refuses the published default of the monitor role" up_refuses "$(env_file y DLAP_MONITOR_DB_PASSWORD=dlap_monitor_pass)" "DLAP_MONITOR_DB_PASSWORD is a published quickstart default"
  expect ENV-24 "refuses the published default of the backup role" up_refuses "$(env_file z DLAP_BACKUP_DB_PASSWORD=dlap_backup_pass)" "DLAP_BACKUP_DB_PASSWORD is a published quickstart default"
  expect ENV-25 "refuses a database role password used twice" up_refuses "$(env_file aa DA_REPORTING_DB_PASSWORD=db-0123-shared DA_AUTOLABEL_DB_PASSWORD=db-0123-shared)" "has the same value as another database password"
  expect ENV-26 "refuses a database role password equal to the superuser's" up_refuses "$(env_file ab DA_REPORTING_DB_PASSWORD=pg-0123456789abcdef)" "DA_REPORTING_DB_PASSWORD has the same value as another database password"
fi

# ---------------------------------------------------------------------------------------------------
# R: nightly-reset.sh without Docker
# ---------------------------------------------------------------------------------------------------

state_with_status() { # state_with_status <name> <json>
  local d="$TMP/state-$1"
  mkdir -p "$d/flags"
  [ -z "${2:-}" ] || printf '%s\n' "$2" > "$d/last-reset.json"
  echo "$d"
}
reset_check() { # reset_check <state dir> [extra args]
  local d="$1"; shift
  DEMO_STATE_DIR="$(native "$d")" bash "$DEMO_DIR/scripts/nightly-reset.sh" --check "$@"
}
fake_golden() { # fake_golden <name>: a golden folder with two databases and valid checksums
  local g="$TMP/golden-$1"
  mkdir -p "$g"
  printf 'dump-a' > "$g/dlap_common.dump"; printf 'dump-b' > "$g/da_catalog.dump"
  ( cd "$g" && sha256sum dlap_common.dump da_catalog.dump > SHA256SUMS )
  printf 'CREATED_UTC=2026-01-01T00:00:00Z\nDATABASES="dlap_common da_catalog"\nASSETS=16\nLABEL_ASSIGNMENTS=3\n' > "$g/manifest.env"
  echo "$g"
}
recent_utc() { date -u -d "$1 hours ago" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u +%Y-%m-%dT%H:%M:%SZ; }

dry_run_ok() {
  local g d out
  g="$(fake_golden dry)"; d="$TMP/state-dry"; mkdir -p "$d"
  out="$(DEMO_STATE_DIR="$(native "$d")" bash "$DEMO_DIR/scripts/nightly-reset.sh" --dry-run --golden "$g" 2>&1)" || { echo "$out"; return 1; }
  local last=0 step pos
  for step in "verifying the golden dump" "maintenance page ON" "stopping the JVM services" "restoring dlap_common" \
              "restoring da_catalog" "wiping Kafka" "starting the JVM services in two waves" "maintenance page OFF"; do
    pos="$(grep -n -F "$step" <<<"$out" | head -n 1 | cut -d: -f1)"
    [ -n "$pos" ] || { echo "step missing: $step"; echo "$out"; return 1; }
    [ "$pos" -gt "$last" ] || { echo "step out of order: $step"; return 1; }
    last="$pos"
  done
  [ ! -e "$d/flags/maintenance" ] && [ ! -e "$d/last-reset.json" ] || { echo "a dry run changed the state folder"; return 1; }
}

corrupt_golden_refused() {
  local g d rc
  g="$(fake_golden bad)"; d="$TMP/state-bad"; mkdir -p "$d"
  printf 'tampered' > "$g/dlap_common.dump"
  DEMO_STATE_DIR="$(native "$d")" bash "$DEMO_DIR/scripts/nightly-reset.sh" --golden "$g" >/dev/null 2>&1; rc=$?
  [ "$rc" -eq 1 ] || { echo "exit $rc, wanted 1"; return 1; }
  grep -q '"status":"failed"' "$d/last-reset.json" || { echo "no failed status written"; return 1; }
  [ ! -e "$d/flags/maintenance" ] || { echo "the maintenance page was switched on before the golden dump was verified"; return 1; }
}

no_golden_refused() {
  local d out
  d="$TMP/state-none"; mkdir -p "$d"
  out="$(DEMO_STATE_DIR="$(native "$d")" bash "$DEMO_DIR/scripts/nightly-reset.sh" 2>&1)" && return 1
  grep -q "no golden dump" <<<"$out"
}

if block R; then
  expect R-01 "--check fails when no reset has run"            bash -c '! "$@"' _ reset_check "$(state_with_status none '')"
  expect R-02 "--check fails when the last reset failed"       bash -c '! "$@"' _ reset_check "$(state_with_status failed '{"status":"failed","finished_utc":"'"$(recent_utc 1)"'"}')"
  expect R-03 "--check passes after a recent good reset"       reset_check "$(state_with_status good '{"status":"ok","finished_utc":"'"$(recent_utc 2)"'"}')"
  expect R-04 "--check fails when the good reset is too old"   bash -c '! "$@"' _ reset_check "$(state_with_status old '{"status":"ok","finished_utc":"'"$(recent_utc 50)"'"}')"
  expect R-05 "--check honours --max-age-hours"                reset_check "$(state_with_status wide '{"status":"ok","finished_utc":"'"$(recent_utc 50)"'"}')" --max-age-hours 72
  expect R-06 "--dry-run lists every step in order and changes nothing" dry_run_ok
  expect R-07 "a corrupt golden dump is refused before anything is touched" corrupt_golden_refused
  expect R-08 "a missing golden dump is refused with the way to create it"  no_golden_refused
fi

# ---------------------------------------------------------------------------------------------------
# F: files
# ---------------------------------------------------------------------------------------------------

all_files() { find "$DEMO_DIR" -type f ! -path "$DEMO_DIR/spa/*" ! -path "$DEMO_DIR/state/*" ! -name '.env.demo' | sort; }

shell_syntax() { local f; for f in "$DEMO_DIR"/*.sh "$DEMO_DIR"/scripts/*.sh; do bash -n "$f" || { echo "syntax error in $f"; return 1; }; done; }

licence_headers() {
  local f bad=0
  for f in $(all_files); do
    case "$f" in *.sh|*.yml|*.conf|*.html|*.md|*/Caddyfile|*.env.demo.example) ;; *) continue ;; esac
    head -n 30 "$f" | grep -q "Licensed to the Apache Software Foundation" || { echo "no licence header: $f"; bad=1; }
  done
  return $bad
}

no_dashes() {
  local em en
  em=$'\xe2\x80\x94'; en=$'\xe2\x80\x93'
  ! all_files | xargs -d '\n' grep -l -e "$em" -e "$en" 2>/dev/null
}

ignored_by_git() {
  local f
  for f in deployment/environments/demo/.env.demo deployment/environments/demo/spa/index.html \
           deployment/environments/demo/state/golden/x.dump deployment/environments/demo/state/flags/maintenance; do
    git -C "$(native "$REPO_ROOT")" check-ignore -q "$f" || { echo "$f is not ignored"; return 1; }
  done
  ! git -C "$(native "$REPO_ROOT")" check-ignore -q deployment/environments/demo/.env.demo.example
}

lf_endings() { ! all_files | xargs -d '\n' grep -l $'\r' 2>/dev/null; }

# Q-388: a Windows checkout with autocrlf would turn the realm JSON into CRLF, which F-05 (and the
# Keycloak import of a mounted file) must never see. .gitattributes pins every JSON file to LF.
json_pinned_to_lf() {
  local f out n=0
  for f in $(all_files | grep '\.json$'); do
    n=$((n + 1))
    out="$(git -C "$(native "$REPO_ROOT")" check-attr text eol -- "${f#"$REPO_ROOT"/}" | tr '\n' ' ')"
    [[ "$out" == *"text: set"* && "$out" == *"eol: lf"* ]] || { echo "$f is not pinned to LF: $out"; return 1; }
  done
  [ "$n" -gt 0 ] || { echo "no JSON file found: the check would pass without checking anything"; return 1; }
}

if block F; then
  expect F-01 "every shell script parses"                         shell_syntax
  expect F-02 "every file carries the Apache licence header"      licence_headers
  expect F-03 "no em-dash or en-dash anywhere in the folder"      no_dashes
  expect F-04 "state, the SPA build and the real env file are ignored; the example is not" ignored_by_git
  expect F-05 "no file has CRLF line endings"                     lf_endings
  expect F-06 "every JSON file is pinned to LF by .gitattributes" json_pinned_to_lf
fi

# ---------------------------------------------------------------------------------------------------
# N: the real nginx.demo.conf behind a stub
# ---------------------------------------------------------------------------------------------------

NGINX_IMG="nginx:1.27-alpine"

stub_conf() { # stub_conf <name> <marker> <listen port>
  # Two servers: the front reads and buffers the whole request body (so a size limit upstream of it is
  # exercised), then hands it to an echo that answers with what it received.
  cat > "$TMP/stub-$1.conf" <<EOF
events {}
http {
  client_max_body_size 10m;
  server {
    listen 127.0.0.1:8099;
    location / {
      default_type application/json;
      return 200 '{"svc":"$2","uri":"\$request_uri","method":"\$request_method","auth":"\$http_authorization","xff":"\$http_x_forwarded_for","host":"\$host"}';
    }
  }
  server {
    listen $3;
    location / {
      proxy_set_header Host \$host;
      proxy_pass http://127.0.0.1:8099;
    }
  }
}
EOF
}

start_stub() { # start_stub <alias> <marker> <network> <port>
  stub_conf "$1" "$2" "$4"
  docker run -d --name "demo01t-$1" --network "$3" --network-alias "$1" \
    -v "$(native "$TMP/stub-$1.conf"):/etc/nginx/nginx.conf:ro" "$NGINX_IMG" >/dev/null || { echo "start_stub $1 failed" >&2; return 1; }
  CONTAINERS+=("demo01t-$1")
}

GW_PORT=""
code() { curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@"; }
body() { curl -s --max-time 10 "$@"; }
hdrs() { curl -s -D - -o /dev/null --max-time 10 "$@" | tr -d '\r'; }

nginx_suite() {
  local net="demo01t-net-gw" html="$TMP/html"
  docker network create "$net" >/dev/null 2>&1 || { t_no N-00 "docker network for the gateway tests" "cannot create a network"; return; }
  NETWORKS+=("$net")
  mkdir -p "$html/assets"
  printf '<!doctype html><title>SPA-SHELL</title>' > "$html/index.html"
  printf 'console.log(1)' > "$html/assets/app-abc123.js"

  start_stub da-catalog catalog "$net" 8080
  start_stub da-policyengine policyengine "$net" 8080
  start_stub da-reporting reporting "$net" 8080

  expect N-00 "nginx -t accepts the configuration" \
    docker run --rm -v "$(native "$DEMO_DIR/gateway/nginx.demo.conf"):/etc/nginx/nginx.conf:ro" "$NGINX_IMG" nginx -t

  # The same hardening as the compose overlay: no capabilities but three, no privilege gain, read-only.
  docker run -d --name demo01t-gw --network "$net" -p 127.0.0.1::8081 \
    --read-only --tmpfs /tmp:size=32m,mode=1777 --cap-drop ALL --cap-add CHOWN --cap-add SETGID --cap-add SETUID \
    --security-opt no-new-privileges:true --pids-limit 100 --memory 64m \
    -v "$(native "$DEMO_DIR/gateway/nginx.demo.conf"):/etc/nginx/nginx.conf:ro" \
    -v "$(native "$html"):/usr/share/nginx/html:ro" "$NGINX_IMG" >/dev/null || { t_no N-01 "the gateway starts hardened" "docker run failed"; return; }
  CONTAINERS+=("demo01t-gw")
  GW_PORT="$(docker port demo01t-gw 8081/tcp | head -n 1 | sed 's/.*://' | tr -d '\r')"
  local i
  for i in $(seq 1 30); do [ "$(code "http://127.0.0.1:$GW_PORT/gateway-health")" = "200" ] && break; sleep 1; done
  local B="http://127.0.0.1:$GW_PORT"

  expect N-01 "the gateway runs read-only without capabilities and answers its health path" test "$(code "$B/gateway-health")" = "200"
  expect N-02 "GET / serves the SPA shell"                         bash -c '[[ "$(curl -s --max-time 10 "$1/")" == *SPA-SHELL* ]]' _ "$B"
  expect N-03 "a client-side route falls back to index.html with 200" bash -c '[[ "$(curl -s -w "%{http_code}" --max-time 10 "$1/policies/new/step-2")" == *SPA-SHELL*200 ]]' _ "$B"
  expect N-04 "hashed assets are cached for a year"                bash -c 'curl -s -D - -o /dev/null --max-time 10 "$1/assets/app-abc123.js" | tr -d "\r" | grep -qi "^cache-control: max-age=31536000"' _ "$B"
  expect N-05 "index.html is not cached"                           bash -c 'curl -s -D - -o /dev/null --max-time 10 "$1/index.html" | tr -d "\r" | grep -qi "^cache-control: no-cache"' _ "$B"
  expect N-06 "POST .../policyengine/events/test-rule answers 403" test "$(code -X POST "$B/api/v1/policyengine/events/test-rule")" = "403"
  expect N-07 "test-rule is refused under any prefix and with a suffix" bash -c '[ "$(curl -s -o /dev/null -w "%{http_code}" -X POST "$1/api/v1/events/test-rule")" = 403 ] && [ "$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$1/anything/test-rule/x")" = 403 ]' _ "$B"
  expect N-08 "actuator, api-docs and Swagger UI answer 404 on every service path" bash -c '
      for p in /api/v1/catalog/actuator/health /actuator/health /api/v1/policyengine/api-docs /api/v1/reporting/v3/api-docs /swagger-ui.html /api/v1/catalog/swagger-ui/index.html; do
        [ "$(curl -s -o /dev/null -w "%{http_code}" "$1$p")" = 404 ] || { echo "$p"; exit 1; }
      done' _ "$B"
  expect N-09 "a catalog call reaches the catalog with path, query and bearer token intact" bash -c '
      out="$(curl -s -H "Authorization: Bearer abc.def" "$1/api/v1/catalog/assets?page=0&size=5")"
      [[ "$out" == *\"svc\":\"catalog\"* && "$out" == *"/api/v1/catalog/assets?page=0&size=5"* && "$out" == *"Bearer abc.def"* ]] || { echo "$out"; exit 1; }' _ "$B"
  expect N-10 "policy and reporting calls reach their own services" bash -c '
      [[ "$(curl -s "$1/api/v1/policyengine/policies")" == *policyengine* && "$(curl -s "$1/api/v1/reporting/dashboards/x")" == *reporting* && "$(curl -s "$1/api/v1/events/stream")" == *policyengine* ]]' _ "$B"
  expect N-11 "POST /access/v1/evaluation reaches the policy engine unchanged" bash -c '
      out="$(curl -s -X POST -H "Content-Type: application/json" -d "{}" "$1/access/v1/evaluation")"
      [[ "$out" == *policyengine* && "$out" == *"/access/v1/evaluation"* && "$out" == *POST* ]] || { echo "$out"; exit 1; }' _ "$B"
  expect N-12 "an unknown /api path is a JSON 404, not the SPA" bash -c '
      out="$(curl -s -w "|%{http_code}" "$1/api/v1/nothing/here")"; [[ "$out" == *"not found"*"|404" && "$out" != *SPA-SHELL* ]]' _ "$B"
  expect N-13 "a body over 256k is refused with 413, one of 100k passes" bash -c '
      head -c 300000 /dev/zero | tr "\0" a > "$2/big"; head -c 100000 /dev/zero | tr "\0" a > "$2/small"
      [ "$(curl -s -o /dev/null -w "%{http_code}" -X POST --data-binary @"$3/big" "$1/api/v1/catalog/assets")" = 413 ] &&
      [ "$(curl -s -o /dev/null -w "%{http_code}" -X POST --data-binary @"$3/small" "$1/api/v1/catalog/assets")" = 200 ]' _ "$B" "$TMP" "$(native "$TMP")"
  expect N-14 "the AuthZEN route is rate limited: a burst gets 429 and the first calls get 200" bash -c '
      ok=0; limited=0
      for i in $(seq 1 30); do c="$(curl -s -o /dev/null -w "%{http_code}" -X POST "$1/access/v1/evaluation")"; [ "$c" = 200 ] && ok=$((ok+1)); [ "$c" = 429 ] && limited=$((limited+1)); done
      echo "ok=$ok limited=$limited"; [ "$ok" -ge 3 ] && [ "$limited" -ge 5 ]' _ "$B"
  sleep 2
  expect N-15 "a service that is not running answers a JSON 503 with Retry-After, and the gateway stays up" bash -c '
      h="$(curl -s -D - -w "|%{http_code}" "$1/api/v1/discovery/statistics" | tr -d "\r")"
      [[ "$h" == *"|503" && "$h" == *"unavailable"* && "$h" == *[Rr]etry-[Aa]fter* ]] && [ "$(curl -s -o /dev/null -w "%{http_code}" "$1/gateway-health")" = 200 ]' _ "$B"
  expect N-16 "TRACE answers 405" test "$(code -X TRACE "$B/")" = "405"
  expect N-17 "a dot file answers 404" test "$(code "$B/.env")" = "404"
  expect N-18 "browser headers are present on the SPA and on an error" bash -c '
      for p in / /api/v1/nothing/here; do
        h="$(curl -s -D - -o /dev/null "$1$p" | tr -d "\r")"
        for want in "x-content-type-options: nosniff" "x-frame-options: DENY" "referrer-policy: strict-origin-when-cross-origin" "frame-ancestors"; do
          grep -qi "$want" <<<"$h" || { echo "$p lacks $want"; exit 1; }
        done
      done' _ "$B"
  expect N-19 "the Server header does not name the nginx version" bash -c '! curl -s -D - -o /dev/null "$1/" | grep -qiE "^server: nginx/[0-9]"' _ "$B"

  # The client address comes from X-Forwarded-For (the docker bridge is in a trusted range), and the
  # query string never reaches the access log.
  curl -s -o /dev/null -H "X-Forwarded-For: 203.0.113.9" "$B/?code=SECRETCODE123&state=abc" || true
  sleep 1
  expect N-20 "the access log shows the forwarded client address" bash -c 'docker logs demo01t-gw 2>&1 | grep -qE "^203\.0\.113\.9 GET /(index\.html)? 200"'
  expect N-21 "the access log never holds a query string or an OIDC code" bash -c '! docker logs demo01t-gw 2>&1 | grep -q "SECRETCODE123"'

  # DNS is resolved per request: replace the catalog container so that it gets a new address, and the
  # gateway finds it without a reload. (A literal upstream name would keep calling the old address.)
  local old_ip new_ip
  old_ip="$(docker inspect --format '{{(index .NetworkSettings.Networks "'"$net"'").IPAddress}}' demo01t-da-catalog | tr -d '\r')"
  docker rm -f demo01t-da-catalog >/dev/null 2>&1
  docker run -d --name demo01t-squat --network "$net" "$NGINX_IMG" >/dev/null 2>&1; CONTAINERS+=("demo01t-squat")
  start_stub da-catalog catalog2 "$net" 8080
  new_ip="$(docker inspect --format '{{(index .NetworkSettings.Networks "'"$net"'").IPAddress}}' demo01t-da-catalog | tr -d '\r')"
  sleep 7
  expect N-22 "a restarted service with a new address is found again without reloading nginx" bash -c '
      [ "$2" != "$3" ] || { echo "the address did not change ($2); the test proves nothing"; exit 1; }
      [[ "$(curl -s "$1/api/v1/catalog/assets")" == *catalog2* ]]' _ "$B" "$old_ip" "$new_ip"
  expect N-23 "the gateway container is still running after all of this" test "$(docker inspect --format '{{.State.Running}}' demo01t-gw | tr -d '\r')" = "true"
}

# ---------------------------------------------------------------------------------------------------
# C: the real Caddyfile behind stubs
# ---------------------------------------------------------------------------------------------------

CADDY_IMG="$(grep -o 'caddy:[^ ]*@sha256:[0-9a-f]*' "$DEMO_DIR/docker-compose.demo.yml" | head -n 1)"
CADDY_HTTPS=""
CADDY_HTTP=""

start_caddy() { # start_caddy <name> <plain http address, or :65080 for none> [extra docker run arguments] : the Caddyfile as the overlay mounts it
  local name="demo01t-$1" httpaddr="$2" net="demo01t-net-edge"
  shift 2
  docker run -d --name "$name" --network "$net" -p 127.0.0.1::80 -p 127.0.0.1::443 \
    -e DEMO_APP_HOST=demo.localhost -e DEMO_ID_HOST=id.localhost -e DEMO_TLS=internal -e DEMO_APP_HTTP_ADDRESS="$httpaddr" -e GOMEMLIMIT=64MiB \
    --read-only --tmpfs /tmp:size=16m,mode=1777 --cap-drop ALL --cap-add NET_BIND_SERVICE --security-opt no-new-privileges:true \
    --pids-limit 200 --memory 96m "$@" \
    -v "$(native "$DEMO_DIR/caddy/Caddyfile"):/etc/caddy/Caddyfile:ro" \
    -v "$(native "$DEMO_DIR/maintenance"):/srv/maintenance:ro" \
    -v "$(native "$TMP/flags"):/srv/flags:ro" \
    -v "${name}-data:/data" -v "${name}-config:/config" "$CADDY_IMG" >/dev/null || return 1
  CONTAINERS+=("$name")
  CADDY_HTTPS="$(docker port "$name" 443/tcp | head -n 1 | sed 's/.*://' | tr -d '\r')"
  CADDY_HTTP="$(docker port "$name" 80/tcp | head -n 1 | sed 's/.*://' | tr -d '\r')"
  local i
  for i in $(seq 1 30); do
    [ "$(docker inspect --format '{{.State.Running}}' "$name" | tr -d '\r')" = "true" ] || return 1
    [ "$(curl -sk -o /dev/null -w '%{http_code}' --max-time 3 --resolve "demo.localhost:$CADDY_HTTPS:127.0.0.1" "https://demo.localhost:$CADDY_HTTPS/" 2>/dev/null)" != "000" ] && return 0
    sleep 1
  done
  return 1
}

app() { curl -sk --max-time 10 --resolve "demo.localhost:$CADDY_HTTPS:127.0.0.1" "$@"; }
idp() { curl -sk --max-time 10 --resolve "id.localhost:$CADDY_HTTPS:127.0.0.1" "$@"; }

caddy_suite() {
  local net="demo01t-net-edge" html="$TMP/html-edge"
  mkdir -p "$TMP/flags" "$html/assets"
  printf '<!doctype html><title>SPA-SHELL</title>' > "$html/index.html"
  printf '<html><body><script>parent.postMessage(location.href, location.origin);</script></body></html>' > "$html/silent-check-sso.html"
  docker network create "$net" >/dev/null 2>&1 || { t_no C-00 "docker network for the edge tests" "cannot create a network"; return; }
  NETWORKS+=("$net")
  if [ -z "$CADDY_IMG" ]; then t_no C-00 "the overlay names a pinned Caddy image" "no caddy image with a digest in docker-compose.demo.yml"; return; fi

  # Behind Caddy: the REAL nginx gateway (so the two layers' headers meet, as they do in the stack),
  # a stub catalog behind it, and a stub Keycloak.
  start_stub da-catalog catalog "$net" 8080
  start_stub keycloak KC "$net" 8080
  docker run -d --name demo01t-gateway --network "$net" --network-alias gateway \
    --read-only --tmpfs /tmp:size=32m,mode=1777 --cap-drop ALL --cap-add CHOWN --cap-add SETGID --cap-add SETUID \
    --security-opt no-new-privileges:true --pids-limit 100 --memory 64m \
    -v "$(native "$DEMO_DIR/gateway/nginx.demo.conf"):/etc/nginx/nginx.conf:ro" \
    -v "$(native "$html"):/usr/share/nginx/html:ro" "$NGINX_IMG" >/dev/null || { t_no C-00 "the real gateway starts" "docker run failed"; return; }
  CONTAINERS+=("demo01t-gateway")

  expect C-00 "caddy validate accepts the Caddyfile in both TLS modes and with the local HTTP address" bash -c '
      for envs in "-e DEMO_TLS=auto" "-e DEMO_TLS=internal" "-e DEMO_TLS=internal -e DEMO_APP_HTTP_ADDRESS=http://localhost"; do
        docker run --rm $envs -v "$1:/etc/caddy/Caddyfile:ro" "$2" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1 | grep -q "Valid configuration" || exit 1
      done' _ "$(native "$DEMO_DIR/caddy/Caddyfile")" "$CADDY_IMG"

  if ! start_caddy plain http://demo.localhost; then t_no C-01 "caddy starts hardened" "$(docker logs demo01t-plain 2>&1 | tail -5)"; return; fi
  local A="https://demo.localhost:$CADDY_HTTPS" I="https://id.localhost:$CADDY_HTTPS"
  local ra="demo.localhost:$CADDY_HTTPS:127.0.0.1"
  local i
  for i in $(seq 1 20); do [[ "$(app "$A/")" == *SPA-SHELL* ]] && break; sleep 1; done

  c_check() { # c_check <id> <claim> <command...>
    local id="$1" claim="$2"; shift 2
    selected "$id" || return 0
    if "$@" >"$TMP/last.out" 2>&1; then t_ok "$id" "$claim"; else t_no "$id" "$claim" "$(head -c 500 "$TMP/last.out" | tr '\n' ' ')"; fi
  }
  contains() { [[ "$1" == *"$2"* ]]; }

  c_check C-01 "caddy runs read-only without capabilities; the app host serves the SPA and the API through the real gateway over TLS" \
    bash -c '[[ "$1" == *SPA-SHELL* && "$2" == *\"svc\":\"catalog\"* && "$2" == *"/api/v1/catalog/assets?x=1"* ]]' _ "$(app "$A/")" "$(app "$A/api/v1/catalog/assets?x=1")"
  c_check C-02 "a client-sent X-Forwarded-For is not trusted" bash -c '! [[ "$1" == *6.6.6.6* ]]' _ "$(app -H 'X-Forwarded-For: 6.6.6.6' "$A/api/v1/catalog/assets")"
  c_check C-03 "the Keycloak host proxies the realm endpoints" contains "$(idp "$I/realms/dlap/.well-known/openid-configuration")" '"svc":"KC"'
  c_check C-04 "the Keycloak host proxies the static resources" contains "$(idp "$I/resources/abc/login/keycloak/css/login.css")" '"svc":"KC"'
  local p out denied_ok=1 denied_list=""
  for p in /admin /admin/ /admin/master/console/ /admin/realms/dlap/users /metrics /health /health/ready /q/health /management/x \
           /realms/master /realms/master/ /realms/master/protocol/openid-connect/token /Realms/Master /realms//master /ADMIN/ /%61dmin/ /realms/%6Daster; do
    out="$(idp -w '|%{http_code}' "$I$p")"
    if ! contains "$out" '|404' || contains "$out" '"svc":"KC"'; then denied_ok=0; denied_list="$denied_list $p($out)"; fi
  done
  c_check C-05 "the admin console, the admin API, metrics, health and the master realm are never proxied (17 paths, case and encoding variants)" bash -c '[ "$1" = 1 ] || { echo "$2"; exit 1; }' _ "$denied_ok" "$denied_list"
  c_check C-06 "any other Keycloak path answers 404 without reaching Keycloak" bash -c '[[ "$1" == *"|404" && "$1" != *KC* ]]' _ "$(idp -w '|%{http_code}' "$I/")$(idp -w '|%{http_code}' "$I/js/keycloak.js")"
  c_check C-07 "path traversal into the master realm is not proxied" bash -c '[[ "$1" != *KC* ]]' _ "$(idp --path-as-is "$I/realms/dlap/../master/protocol/openid-connect/certs")"

  local ha hi hs he
  ha="$(app -D - -o /dev/null "$A/" | tr -d '\r')"
  he="$(app -D - -o /dev/null "$A/api/v1/nothing/here" | tr -d '\r')"
  hi="$(idp -D - -o /dev/null "$I/realms/dlap/x" | tr -d '\r')"
  hs="$(app -D - -o /dev/null "$A/silent-check-sso.html" | tr -d '\r')"
  c_check C-08 "the app host sends HSTS, nosniff, referrer policy, frame denial and a CSP naming the Keycloak origin" bash -c '
      for want in "strict-transport-security" "x-content-type-options: nosniff" "referrer-policy" "x-frame-options: DENY" "content-security-policy: default-src .self." "connect-src .self. https://id.localhost" "frame-src .self. https://id.localhost" "frame-ancestors .none."; do
        grep -qiE "$want" <<<"$1" || { echo "missing: $want"; exit 1; }
      done' _ "$ha"
  c_check C-09 "each security header appears exactly once on the SPA, on an API error and on the silent sign-in page (Caddy replaces nginx's copy)" bash -c '
      for h in "$1" "$2" "$3"; do
        for name in content-security-policy x-frame-options x-content-type-options referrer-policy permissions-policy strict-transport-security; do
          n="$(grep -ci "^$name:" <<<"$h" || true)"; [ "$n" = 1 ] || { echo "$name appears $n times"; exit 1; }
        done
      done' _ "$ha" "$he" "$hs"
  c_check C-10 "the Keycloak host sends HSTS and nosniff and sets no frame header that would break its session iframe" bash -c '
      grep -qi "strict-transport-security" <<<"$1" && grep -qi "x-content-type-options: nosniff" <<<"$1" && ! grep -qi "^x-frame-options" <<<"$1"' _ "$hi"
  c_check C-11 "no response names a server or advertises HTTP/3" bash -c '! grep -qiE "^(server:|alt-svc:)" <<<"$1$2$3"' _ "$ha" "$hi" "$he"
  c_check C-12 "the silent sign-in page may run its one inline script and be framed by the app only" bash -c '
      grep -qi "^x-frame-options: SAMEORIGIN" <<<"$1" && grep -qi "^content-security-policy: default-src .none.; script-src .unsafe-inline.; frame-ancestors .self.$" <<<"$1"' _ "$hs"
  c_check C-13 "a request body over 256 KB is refused at the edge" bash -c '
      head -c 300000 /dev/zero | tr "\0" a > "$3/big"
      [ "$(curl -sk -o /dev/null -w "%{http_code}" --resolve "$2" -X POST --data-binary @"$4/big" "$1/api/v1/catalog/assets")" = 413 ]' _ "$A" "$ra" "$TMP" "$(native "$TMP")"

  # Maintenance: a flag file switches the app host to a 503 page and a JSON body for the API, and the
  # Keycloak host keeps answering.
  printf '2026-10-19T03:00:00Z\n' > "$TMP/flags/maintenance"
  sleep 1
  local mp mj mi
  mp="$(app -D - "$A/")"; mj="$(app -w '|%{http_code}' "$A/api/v1/catalog/assets")"; mi="$(idp "$I/realms/dlap/x")"
  c_check C-14 "with the flag set the app host answers 503 with the maintenance page and Retry-After" bash -c '
      tr -d "\r" <<<"$1" | head -n 1 | grep -q " 503" && grep -qi "demo is resetting" <<<"$1" && grep -qi "^retry-after:" <<<"$1"' _ "$mp"
  c_check C-15 "with the flag set the API answers 503 JSON" bash -c '[[ "$1" == *maintenance*"|503" ]]' _ "$mj"
  c_check C-16 "with the flag set the Keycloak host keeps answering" contains "$mi" '"svc":"KC"'
  c_check C-17 "the maintenance page states that the data is synthetic" contains "$mp" "Demo on synthetic data. No cloud account connected. Not production."
  rm -f "$TMP/flags/maintenance"
  sleep 1
  c_check C-18 "removing the flag brings the app back" contains "$(app "$A/")" 'SPA-SHELL'

  # The gateway is down: the same page, the same 503.
  docker stop demo01t-gateway >/dev/null 2>&1
  sleep 1
  local dp dj
  dp="$(app -w '|%{http_code}' "$A/")"; dj="$(app -w '|%{http_code}' "$A/api/v1/catalog/assets")"
  c_check C-19 "with the gateway down the app host answers the maintenance page with 503" bash -c '[[ "$1" == *"demo is resetting"*"|503" ]]' _ "$dp"
  c_check C-20 "with the gateway down the API answers 503 JSON" bash -c '[[ "$1" == *unavailable*"|503" ]]' _ "$dj"
  docker start demo01t-gateway >/dev/null 2>&1
  for i in $(seq 1 20); do [[ "$(app "$A/")" == *SPA-SHELL* ]] && break; sleep 1; done
  c_check C-21 "the app is back when the gateway is back" contains "$(app "$A/")" 'SPA-SHELL'

  # The two ways HTTP is handled.
  c_check C-22 "with DEMO_APP_HTTP_ADDRESS set, plain HTTP on port 80 serves the app (local check mode)" contains "$(curl -s --max-time 10 --resolve "demo.localhost:$CADDY_HTTP:127.0.0.1" "http://demo.localhost:$CADDY_HTTP/api/v1/catalog/assets")" '"svc":"catalog"'
  if start_caddy redirect :65080; then
    c_check C-23 "by default there is no plain-HTTP route: HTTP is redirected to HTTPS" bash -c '
        h="$(curl -s -D - -o /dev/null --max-time 10 --resolve "demo.localhost:$1:127.0.0.1" "http://demo.localhost:$1/some/path" | tr -d "\r")"
        head -n 1 <<<"$h" | grep -qE " 30[78]" && grep -qi "^location: https://demo.localhost" <<<"$h"' _ "$CADDY_HTTP"
  else
    t_no C-23 "by default there is no plain-HTTP route: HTTP is redirected to HTTPS" "the second Caddy did not start: $(docker logs demo01t-redirect 2>&1 | tail -3)"
  fi

  # The policy names the Keycloak port of a rehearsal, and takes the extra sources of the UI's third-party assets.
  if start_caddy extras http://demo.localhost -e DEMO_ID_PORT_SUFFIX=:18444 -e DEMO_CSP_IMG_EXTRA="https://images.unsplash.com https://res.cloudinary.com" -e DEMO_CSP_STYLE_EXTRA=https://fonts.googleapis.com -e DEMO_CSP_FONT_EXTRA=https://fonts.gstatic.com; then
    c_check C-24 "the CSP carries the Keycloak port suffix and the extra image, style and font sources when they are set" bash -c '
        h="$(curl -s -D - -o /dev/null --max-time 10 --resolve "demo.localhost:$1:127.0.0.1" "http://demo.localhost:$1/" | tr -d "\r")"
        for want in "connect-src .self. https://id.localhost:18444" "frame-src .self. https://id.localhost:18444" "img-src .self. data: https://images.unsplash.com https://res.cloudinary.com" "style-src .self. .unsafe-inline. https://fonts.googleapis.com" "font-src .self. data: https://fonts.gstatic.com"; do
          grep -qiE "$want" <<<"$h" || { echo "missing: $want"; exit 1; }
        done' _ "$CADDY_HTTP"
  else
    t_no C-24 "the CSP carries the Keycloak port suffix and the extra sources when they are set" "the third Caddy did not start: $(docker logs demo01t-extras 2>&1 | tail -3)"
  fi
}

if [ "$DOCKER" = "1" ]; then
  if ! docker info >/dev/null 2>&1; then
    t_no N-00 "Docker is needed for the N and C tests" "docker info failed; use --no-docker to skip them"
  else
    if block N; then nginx_suite; release; fi
    if block C; then caddy_suite; release; fi
  fi
fi

echo
echo "demo tests: $PASS passed, $FAIL failed"
if [ "$FAIL" -gt 0 ]; then
  printf 'failed: %s\n' "${FAILED[*]}"
  exit 1
fi
exit 0
