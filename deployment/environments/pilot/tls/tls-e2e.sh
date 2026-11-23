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
# End-to-end rehearsal of the pilot TLS overlay (SEC-06): a throwaway pilot under its own compose
# project, started with up.sh and checked with smoke-tls.sh, then removed with its volumes.
#
# Steps: take the host-wide harness lock (HARN-01); write fresh key material with gen-certs.sh and a
# fresh environment file with random secrets into a scratch folder; start the pilot (every overlay of
# this folder) with up.sh; wait until every container is up; run smoke-tls.sh; record docker stats;
# stop and remove the project (volumes included) unless --keep. With --monitoring the monitoring and
# actions profiles are on too, and after the smoke test verify-targets.sh asks Prometheus whether every
# scrape target is up (the services are scraped over HTTPS, Q-518).
#
# The quickstart realm has no audience mapper yet (the dlap-api audience the overlay requires, see
# docs/guides/transport-security.md "Audience"). While the shipped realm lacks one, this rehearsal
# imports a copy of it with the mapper added to dlap-ui and dlap-backend, through an extra overlay in
# the scratch folder, and says so. As soon as the shipped realm carries the mapper the copy is not made.
#
# Usage:  tls-e2e.sh [--keep] [--monitoring] [--work DIR] [--port N] [--no-build]
#   --keep      leave the pilot running (stop it later with the printed up.sh command)
#   --monitoring  also start the profiles monitoring and actions, and check the Prometheus targets
#   --work DIR  scratch folder (default: a new temporary folder)
#   --port N    host port of the entry point (default 18443)
#   --no-build  start from the images already built (up -d instead of up -d --build)
#
# Needs Docker, the jars (./gradlew build -x test), the new-ui submodule, openssl, keytool, python3.
# Container names of the quickstart are fixed, so no other DLAP stack may run on the host meanwhile.
#
# Exit: smoke-tls.sh's status; 2 a prerequisite is missing or the pilot did not come up.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PILOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PILOT_DIR/../../.." && pwd)"
KEEP=0
MONITORING=0
WORK=""
PORT=18443
BUILD=1
PROJECT="dlap-tls-e2e"
WAIT_SECONDS="${TLS_E2E_WAIT_SECONDS:-900}"

die() { echo "tls-e2e.sh: $*" >&2; exit 2; }
say() { echo "tls-e2e.sh: $*" >&2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --keep) KEEP=1; shift ;;
    --monitoring) MONITORING=1; shift ;;
    --work) [ $# -ge 2 ] || die "--work needs a folder"; WORK="$2"; shift 2 ;;
    --port) [ $# -ge 2 ] || die "--port needs a number"; PORT="$2"; shift 2 ;;
    --no-build) BUILD=0; shift ;;
    -h|--help) awk '/^# End-to-end rehearsal/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ "$PORT" =~ ^[0-9]+$ ]] || die "--port must be a number"

PYTHON=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys' >/dev/null 2>&1; then PYTHON="$candidate"; break; fi
done
[ -n "$PYTHON" ] || die "python3 is required"
mixed() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# shellcheck source=../../../../e2e-tests/harness-lib.sh
source "$REPO_ROOT/e2e-tests/harness-lib.sh"
harness_lock_acquire "sec06-tls-e2e"

if dk_names="$(docker ps --format '{{.Names}}' | grep -E '^dlap-(postgresql|kafka|keycloak|gateway)$')"; then
  die "another DLAP stack is running on this host ($(echo $dk_names)); stop it first"
fi

[ -n "$WORK" ] || WORK="$(mktemp -d)"
mkdir -p "$WORK/overlays"
say "scratch folder: $WORK"

# --- key material and environment --------------------------------------------------------------

bash "$SCRIPT_DIR/gen-certs.sh" --out "$WORK/tls" --public-host localhost --force || die "gen-certs.sh failed"
hex() { openssl rand -hex "$1"; }
PROFILES=""
[ "$MONITORING" = 1 ] && PROFILES="actions,monitoring"
cat >"$WORK/env.pilot" <<EOF
POSTGRES_PASSWORD=$(hex 16)
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=$(hex 16)
KEYCLOAK_DB_PASSWORD=$(hex 16)
GRAFANA_ADMIN_PASSWORD=$(hex 16)
DLAP_BACKEND_CLIENT_SECRET=$(hex 24)
DLAP_MCP_GATEWAY_CLIENT_SECRET=$(hex 24)
DLAP_ENCRYPTION_KEY=$(hex 32)
GCP_PROJECT_ID=tls-e2e-not-a-project
DLAP_LOGINGEST_GCP_ENABLED=false
DLAP_LOGINGEST_AWS_ENABLED=false
COMPOSE_PROFILES=$PROFILES
PILOT_PUBLIC_ORIGIN=https://localhost:$PORT
DLAP_PILOT_ID_URL=https://localhost:$PORT
DLAP_PILOT_APP_URL=https://localhost:$PORT
PILOT_TLS_BIND_ADDRESS=127.0.0.1
PILOT_TLS_PORT=$PORT
PILOT_TLS_DIR=$(mixed "$WORK/tls")
EOF
# The 13 database role passwords the pilot base overlay requires (SEC-12, ADR-0140), random each run.
bash "$REPO_ROOT/deployment/platform/database/gen-db-passwords.sh" "$WORK/env.pilot" >/dev/null \
  || die "gen-db-passwords.sh failed"

# --- overlays: the shipped ones, plus the realm copy while the shipped realm has no audience -----

cp "$PILOT_DIR"/docker-compose.pilot.*.yml "$WORK/overlays/"
REALM="$REPO_ROOT/deployment/platform/keycloak/keycloak-import/dlap-realm.json"
# The realm writes the audience literally or as the import placeholder ${DLAP_API_AUDIENCE:dlap-api}.
if ! grep -qE '"included.custom.audience": "(dlap-api|\$\{DLAP_API_AUDIENCE:dlap-api\})"' "$REALM"; then
  say "the shipped realm has no dlap-api audience mapper: importing a copy with one (rehearsal only)"
  mkdir -p "$WORK/realm"
  "$PYTHON" - "$REALM" "$WORK/realm/dlap-realm.json" <<'PY'
import json
import sys

realm = json.load(open(sys.argv[1], encoding="utf-8"))
mapper = {"name": "dlap-api-audience", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
          "consentRequired": False,
          "config": {"included.custom.audience": "dlap-api", "id.token.claim": "false", "access.token.claim": "true"}}
for client in realm["clients"]:
    if client["clientId"] in ("dlap-ui", "dlap-backend"):
        client.setdefault("protocolMappers", []).append(dict(mapper))
json.dump(realm, open(sys.argv[2], "w", encoding="utf-8", newline="\n"), indent=2, ensure_ascii=False)
PY
  cat >"$WORK/overlays/docker-compose.pilot.zz-e2e-realm.yml" <<EOF
# Written by tls-e2e.sh: the quickstart realm plus a dlap-api audience mapper (rehearsal only).
services:
  keycloak:
    volumes: !override
      - $(mixed "$WORK/realm"):/opt/keycloak/data/import:ro
      - ../../platform/keycloak/keycloak-health-check.sh:/opt/keycloak/health-check.sh
      - pilot-tls:/tls:ro
EOF
fi

export DLAP_PILOT_DIR="$WORK/overlays" COMPOSE_PROJECT_NAME="$PROJECT"
UP=(bash "$PILOT_DIR/up.sh" --env-file "$WORK/env.pilot")
cleanup() {
  if [ "$KEEP" = 1 ]; then
    # The harness lock ends with this script; anything done to the kept project afterwards must take it again.
    say "left running; the harness lock is released now, so wrap every later command in e2e-tests/with-harness-lock.sh."
    say "Stop with: bash e2e-tests/with-harness-lock.sh -- env DLAP_PILOT_DIR=$WORK/overlays COMPOSE_PROJECT_NAME=$PROJECT ${UP[*]} down -v"
  else
    say "removing the project and its volumes"
    "${UP[@]}" down -v --remove-orphans >"$WORK/down.log" 2>&1 || true
  fi
  harness_lock_release
}
trap cleanup EXIT

# --- start -------------------------------------------------------------------------------------

started="$(date +%s)"
if [ "$BUILD" = 1 ]; then "${UP[@]}" up -d --build; else "${UP[@]}" up -d; fi
up_status=$?
[ "$up_status" = 0 ] || { "${UP[@]}" ps -a; "${UP[@]}" logs --no-color --tail 60 tls-init kafka kafka-init postgresql keycloak 2>&1 | tail -150; die "up.sh failed ($up_status)"; }

# Every long-running container healthy (or running, where it has no healthcheck).
deadline=$((started + WAIT_SECONDS))
while :; do
  pending="$("${UP[@]}" ps --format json 2>/dev/null | "$PYTHON" -c '
import json, sys
text = sys.stdin.read().strip()
rows = json.loads(text) if text.startswith("[") else [json.loads(l) for l in text.splitlines() if l.strip()]
# The backup agent (DR-01) reports unhealthy until it has shipped a first backup; running is enough here.
bad = [r["Service"] + ":" + (r.get("Health") or r.get("State")) for r in rows
       if r.get("State") != "running"
       or (r.get("Health") not in ("", None, "healthy") and r["Service"] != "dlap-backup")]
print(" ".join(bad))')"
  [ -z "$pending" ] && break
  if [ "$(date +%s)" -gt "$deadline" ]; then
    "${UP[@]}" ps -a
    die "not up after ${WAIT_SECONDS}s: $pending"
  fi
  sleep 10
done
say "pilot up after $(( $(date +%s) - started ))s"
# The services connect to Kafka and join their groups shortly after the port opens.
sleep "${TLS_E2E_SETTLE_SECONDS:-60}"

# --- smoke -------------------------------------------------------------------------------------

# With the actions profile on (--monitoring) the smoke test also makes both action executors call the
# policy engine over HTTPS (H7, --executors): it writes two refused probe rows, harmless in a rehearsal.
EXEC_ARGS=()
[ "$MONITORING" = 1 ] && EXEC_ARGS=(--executors)
bash "$SCRIPT_DIR/smoke-tls.sh" --env-file "$WORK/env.pilot" --tls-dir "$WORK/tls" ${EXEC_ARGS[@]+"${EXEC_ARGS[@]}"}
status=$?
if [ "$MONITORING" = 1 ]; then
  # Prometheus is published on 127.0.0.1:9090 by the base overlay. The services' targets are HTTPS.
  say "asking Prometheus whether every target is up"
  sleep "${TLS_E2E_SCRAPE_SECONDS:-45}"
  if ! PROMETHEUS_URL="http://127.0.0.1:9090" bash "$REPO_ROOT/deployment/platform/prometheus/verify-targets.sh"; then
    say "verify-targets.sh failed; the targets that are not up, with Prometheus's reason:"
    curl -fsS "http://127.0.0.1:9090/api/v1/targets?state=active" 2>/dev/null | "$PYTHON" -c '
import json, sys
try:
    for t in json.load(sys.stdin)["data"]["activeTargets"]:
        if t.get("health") != "up":
            print("  %s %s: %s" % (t["labels"].get("instance"), t.get("health"), t.get("lastError") or "no error recorded"))
except Exception as e:
    print("  (could not read the targets: %s)" % e)
' >&2
    status=1
  fi
fi
"${UP[@]}" ps -a >"$WORK/ps.txt" 2>&1
docker stats --no-stream --format '{{.Name}} {{.MemUsage}} {{.CPUPerc}}' >"$WORK/stats.txt" 2>&1
say "docker stats (memory after the smoke test):"
grep -E '^dlap-' "$WORK/stats.txt" >&2 || true
exit "$status"
