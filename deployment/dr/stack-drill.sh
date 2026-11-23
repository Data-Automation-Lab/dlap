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
# Full-stack restore drill on the dev compose stack (DR-02).
#
# DR-01's restore-drill.sh proves the database tier. This drill proves the product: it brings up the real
# quickstart stack (PostgreSQL, Kafka, Keycloak and the ten Java services built from this checkout), fills it
# with data, protects it, destroys it with all its volumes, rebuilds it from the off-host copy alone, and
# checks that it works.
#
#   1. Stack A: the quickstart pair, the pilot's backup overlay (the real agent), the e2e override (fixture
#      replay, dry-run delete, mock archival). Wait until the ten services answer /actuator/health.
#   2. Seed: the demo rows, fixture events replayed through the log spine into the catalog, a Keycloak user
#      with a password set at run time, approvals with two decisions (real audit entries), synthetic audit
#      entries on the streams that have no running writer yet, a marker table written once a second.
#   3. Protect: stop the services (so the live database can be compared exactly), take the row counts and
#      content digests of every table, export the Keycloak realm, take a logical backup, ship both off the
#      host. Then keep writing markers, add audit entries, wait until the agent has shipped the WAL.
#   4. Disaster: the stack and every volume it owns are removed. Only the off-host volume survives.
#   5. Stack B, logical restore: PostgreSQL from the init scripts, restore.sh from the encrypted off-host
#      set, Keycloak from the realm export. The restored tables must equal the digests of step 3.
#      Then the services start and everything is verified (section "Checks" below).
#   6. Stack C, point-in-time restore (WAL replay to the latest shipped moment), same verification, and the
#      tables must equal the state at the disaster, not only at the backup.
#
# Checks after each restore: row counts and content digests of every table equal the recorded ones, the
# Flyway history of every service is intact and unchanged by the start of the services, scripts/audit-verify.sh
# passes on every audit stream, every service answers /actuator/health with 200 and status UP, a login with the
# password set at run time returns a token that the catalog and the policy engine accept, and the seeded
# approvals are still there. The drill records the time of each step (RTO) and the newest marker the restored
# stack holds against the newest marker the lost one wrote (RPO) as numbers.
#
# Safety. The whole run holds the host-wide harness lock (e2e-tests/harness-lib.sh) and uses the compose
# project name derived from this checkout, so it never runs beside another harness and never touches a
# stack of another project. It removes ITS OWN project's volumes; it evicts a foreign dlap-* container
# only through the harness rules (the container, never its volumes). Every secret is generated for the run
# (or taken from DLAP_DRILL_*) and lives in a mode 600 file of the evidence folder, never in this script.
#
# Usage:  stack-drill.sh [options]
#   --restore logical|pitr|both  which restores to run (default both)
#   --rows N                     synthetic log events added to da_logingest before the backup, so that the restore
#                                moves a database of some size (default 200000; 0 for none)
#   --out DIR                    keep the evidence here (digests, logs, timings); default a temp folder
#   --rebuild                    build the service jars with Gradle (default: only when a jar is missing)
#   --keep                       leave the stack, volumes and evidence in place
#   --stop-after PHASE           stop after seed | protect (a stack is left only with --keep)
#   -h, --help                   this text
# Environment (all optional): POSTGRES_PASSWORD, KEYCLOAK_ADMIN_PASSWORD, DLAP_BACKEND_CLIENT_SECRET,
# DLAP_MCP_GATEWAY_CLIENT_SECRET, DLAP_ENCRYPTION_KEY, DLAP_DRILL_LOGIN_PASSWORD, DLAP_DRILL_PASSPHRASE (the
# secrets of the run; each one is generated when it is not exported),
# DLAP_HARNESS_LOCK_TIMEOUT, DLAP_JDK21 (see e2e-tests/harness-lib.sh), DLAP_DRILL_HEALTH_TIMEOUT (seconds
# to wait for the services, default 420).
# Needs: docker with Compose 2.24 or later, curl, openssl, bash 4, the built jars (or a JDK 21 for --rebuild),
# the images postgres:15.1 and the ones of the quickstart (pulled if absent), about 6 GB of free memory.
# Exit: 0 every check passed; 1 a check failed (the evidence folder says which); 2 a refusal or a missing
# prerequisite.

set -euo pipefail
export MSYS_NO_PATHCONV=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEV_DIR="$REPO_ROOT/deployment/environments/dev"
PILOT_DIR="$REPO_ROOT/deployment/environments/pilot"
E2E_DIR="$REPO_ROOT/e2e-tests"
IMAGE="postgres:15.1"

RESTORE=both
ROWS=200000
OUT=""
REBUILD=0
KEEP=0
STOP_AFTER=""

usage() { awk '/^# Full-stack restore drill/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }
refuse() { echo "stack-drill: $*" >&2; exit 2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --restore) [ $# -ge 2 ] || refuse "--restore needs logical, pitr or both"; RESTORE="$2"; shift 2 ;;
    --rows) [ $# -ge 2 ] || refuse "--rows needs a number"; ROWS="$2"; shift 2 ;;
    --out) [ $# -ge 2 ] || refuse "--out needs a folder"; OUT="$2"; shift 2 ;;
    --rebuild) REBUILD=1; shift ;;
    --keep) KEEP=1; shift ;;
    --stop-after) [ $# -ge 2 ] || refuse "--stop-after needs seed or protect"; STOP_AFTER="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) refuse "unknown argument: $1" ;;
  esac
done
case "$RESTORE" in logical|pitr|both) ;; *) refuse "--restore must be logical, pitr or both" ;; esac
[[ "$ROWS" =~ ^[0-9]+$ ]] || refuse "--rows must be a whole number (0 for none)"
case "$STOP_AFTER" in ""|seed|protect) ;; *) refuse "--stop-after must be seed or protect" ;; esac

for tool in docker curl openssl; do command -v "$tool" > /dev/null 2>&1 || refuse "$tool is required"; done
docker info > /dev/null 2>&1 || refuse "the Docker daemon does not answer"
compose_version="$(docker compose version --short 2> /dev/null || true)"
[ -n "$compose_version" ] || refuse "the Docker Compose plugin is missing"
IFS=. read -r cmaj cmin _ <<< "${compose_version#v}"
if [ "${cmaj:-0}" -lt 2 ] || { [ "${cmaj:-0}" -eq 2 ] && [ "${cmin:-0}" -lt 24 ]; }; then refuse "Docker Compose $compose_version is older than 2.24"; fi
[ "${BASH_VERSINFO[0]}" -ge 4 ] || refuse "bash 4 or newer is required"

hostpath() { if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
now() { date +%s; }
say() { printf '%s drill: %s\n' "$(date -u +%H:%M:%S)" "$*"; }

# The host-wide lock, the compose project name of this checkout, DLAP_PORT_* and the JDK come from the harness.
# shellcheck source=../../e2e-tests/harness-lib.sh
source "$E2E_DIR/harness-lib.sh"
harness_init stack-drill "$REPO_ROOT"
PROJECT="$COMPOSE_PROJECT_NAME"

ID="$(date +%H%M%S)$((RANDOM % 900 + 100))"
REMOTE_A="$PROJECT-$ID-offhost"
REMOTE_B="$PROJECT-$ID-offhost-b"
REMOTE_C="$PROJECT-$ID-offhost-c"
KC_IMPORT="$PROJECT-$ID-kcimport"
KC_STAGE="$PROJECT-$ID-kcstage"
if [ -z "$OUT" ]; then OUT="$(mktemp -d "${TMPDIR:-/tmp}/dlap-stack-drill.XXXXXX")"; else mkdir -p "$OUT"; fi
WORK="$OUT/work"
mkdir -p "$WORK"
chmod 700 "$WORK" 2> /dev/null || true
RESULTS="$OUT/results.env"
: > "$RESULTS"
CHECKS_FAILED=0
CHECKS_PASSED=0

record() { echo "$1=$2" >> "$RESULTS"; }
pass() { CHECKS_PASSED=$((CHECKS_PASSED + 1)); say "PASS  $*"; record "CHECK_$CHECKS_PASSED" "pass: $*"; }
fail() { CHECKS_FAILED=$((CHECKS_FAILED + 1)); say "FAIL  $*" >&2; record "CHECK_FAILED_$CHECKS_FAILED" "$*"; }
expect() { # <description> <command...>
  local d="$1"
  shift
  if "$@" > /dev/null 2>&1; then pass "$d"; else fail "$d"; fi
}
wait_for() { # <seconds> <command...>
  local limit="$1" i=0
  shift
  while ! "$@" > /dev/null 2>&1; do
    i=$((i + 2))
    [ "$i" -le "$limit" ] || return 1
    sleep 2
  done
}
rand_hex() { openssl rand -hex "$1"; }

# --- Secrets of the run ---------------------------------------------------------------------------------
# Generated here, written to a mode 600 file under the evidence folder, never part of this script or of git.
# An operator may supply the three that matter by environment instead.

# Each secret is taken from the environment when the operator exports it (POSTGRES_PASSWORD,
# KEYCLOAK_ADMIN_PASSWORD, DLAP_BACKEND_CLIENT_SECRET, DLAP_MCP_GATEWAY_CLIENT_SECRET, DLAP_ENCRYPTION_KEY,
# DLAP_DRILL_PASSPHRASE, DLAP_DRILL_LOGIN_PASSWORD) and generated for the run otherwise.
KEY="${DLAP_ENCRYPTION_KEY:-$(rand_hex 32)}"
FP="$(DLAP_ENCRYPTION_KEY="$KEY" bash "$SCRIPT_DIR/key-escrow.sh" fingerprint)"
PASSFILE="$WORK/passphrase"
if [ -n "${DLAP_DRILL_PASSPHRASE:-}" ]; then printf '%s\n' "$DLAP_DRILL_PASSPHRASE" > "$PASSFILE"; else openssl rand -base64 36 > "$PASSFILE"; fi
chmod 600 "$PASSFILE" 2> /dev/null || true
KC_ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
KC_ADMIN_PASS="${KEYCLOAK_ADMIN_PASSWORD:-$(rand_hex 12)}"
CLIENT_SECRET="${DLAP_BACKEND_CLIENT_SECRET:-$(rand_hex 12)}"
LOGIN_PASS="${DLAP_DRILL_LOGIN_PASSWORD:-$(rand_hex 12)}"
LOGIN_PASS_2="$(rand_hex 12)"
LOGIN_PASS_3="$(rand_hex 12)"
ENVF="$WORK/env.stack"
{
  echo "# Generated by stack-drill.sh. Random unless the operator exported the variable; never reused."
  echo "POSTGRES_PASSWORD=${POSTGRES_PASSWORD:-$(rand_hex 12)}"
  echo "KEYCLOAK_ADMIN=$KC_ADMIN_USER"
  echo "KEYCLOAK_ADMIN_PASSWORD=$KC_ADMIN_PASS"
  echo "DLAP_BACKEND_CLIENT_SECRET=$CLIENT_SECRET"
  echo "DLAP_MCP_GATEWAY_CLIENT_SECRET=${DLAP_MCP_GATEWAY_CLIENT_SECRET:-$(rand_hex 12)}"
  echo "DLAP_ENCRYPTION_KEY=$KEY"
  echo "DLAP_ENCRYPTION_KEY_FINGERPRINT=$FP"
  echo "DLAP_BACKUP_PASSPHRASE_FILE=$(hostpath "$PASSFILE")"
  # Single quotes, as docs/guides/backup-and-restore.md tells an operator: Compose expands $1 and $2 otherwise.
  echo "DLAP_BACKUP_SHIP_CMD='mkdir -p \"\$(dirname \"/remote/\$2\")\" && cp \"\$1\" \"/remote/\$2\"'"
} > "$ENVF"
chmod 600 "$ENVF" 2> /dev/null || true

# --- Compose ------------------------------------------------------------------------------------------

FIXTURES="$(hostpath "$E2E_DIR/fixtures/replay")"
export DLAP_E2E_FIXTURES_DIR="$FIXTURES"
export DLAP_LOGINGEST_REPLAY_ENABLED=true
export DLAP_LOGINGEST_REPLAY_PROVIDER=""
export DLAP_LOGINGEST_DEMO_SEED_ENABLED=false
export DR_STACK_REMOTE="$REMOTE_A"
export DR_STACK_SOURCE="$REMOTE_A"
export DR_STACK_KC_IMPORT="$KC_IMPORT"
KC_RESTORE=0

BASE_FILES=(-f "$(hostpath "$DEV_DIR/docker-compose.yml")"
            -f "$(hostpath "$DEV_DIR/docker-compose.microservices.yml")"
            -f "$(hostpath "$PILOT_DIR/docker-compose.pilot.backup.yml")"
            -f "$(hostpath "$E2E_DIR/docker-compose.e2e.yml")"
            -f "$(hostpath "$SCRIPT_DIR/docker-compose.stack-drill.yml")")
# The array the harness uses to evict a stack that holds the fixed container names (harness-lib.sh).
# shellcheck disable=SC2034  # read by harness_evict_colliding and harness_reset_stack
COMPOSE=(docker compose --env-file "$(hostpath "$ENVF")" "${BASE_FILES[@]}")

dc() {
  if [ "$KC_RESTORE" -eq 1 ]; then
    docker compose --env-file "$(hostpath "$ENVF")" "${BASE_FILES[@]}" -f "$(hostpath "$SCRIPT_DIR/docker-compose.stack-drill.restore.yml")" "$@"
  else
    docker compose --env-file "$(hostpath "$ENVF")" "${BASE_FILES[@]}" "$@"
  fi
}

APP_SERVICES=(da-discovery da-catalog da-policyengine da-reporting da-autolabel da-autoarchival da-autodelete da-autocompliance da-admin-service da-logingest)
declare -A PORT=(
  [da-discovery]="$DLAP_PORT_DISCOVERY" [da-catalog]="$DLAP_PORT_CATALOG" [da-policyengine]="$DLAP_PORT_POLICYENGINE"
  [da-reporting]="$DLAP_PORT_REPORTING" [da-autolabel]="$DLAP_PORT_AUTOLABEL" [da-autoarchival]="$DLAP_PORT_AUTOARCHIVAL"
  [da-autodelete]="$DLAP_PORT_AUTODELETE" [da-autocompliance]="$DLAP_PORT_AUTOCOMPLIANCE" [da-admin-service]="$DLAP_PORT_ADMIN"
  [da-logingest]="$DLAP_PORT_LOGINGEST")
KC_URL="http://localhost:$DLAP_PORT_KEYCLOAK"
PGC="dlap-postgresql"

# --- Database helpers --------------------------------------------------------------------------------

pg() { docker exec -i "$PGC" psql -X -q -At -v ON_ERROR_STOP=1 -U dlap "$@"; }
pgdb() { local db="$1"; shift; pg -d "$db" "$@"; }
scalar() { local db="$1"; shift; pgdb "$db" -c "$1" | tr -d '\r'; }
# A one-off container of the agent's service: same image, user, mounts and environment as the sidecar.
agentrun() { dc run --rm -T --entrypoint bash dlap-backup "$@"; }
digest() { agentrun /opt/dr/rowcounts.sh --digest "$@" | tr -d '\r'; }
# digest_to <file> [rowcounts.sh arguments]
digest_to() { local f="$1"; shift; digest "$@" > "$f"; }

flyway_history() { # prints database|schema|version|checksum|success|type for every Flyway history table
  local db
  for db in $(pg -d postgres -c "SELECT datname FROM pg_database WHERE NOT datistemplate AND datname <> 'postgres' ORDER BY 1" | tr -d '\r'); do
    pg -d "$db" -F '|' <<'SQL'
SELECT format('SELECT current_database(), %L, version, checksum, success, type FROM %I.flyway_schema_history ORDER BY installed_rank', schemaname, schemaname)
  FROM pg_tables WHERE tablename = 'flyway_schema_history' ORDER BY schemaname
\gexec
SQL
  done | tr -d '\r'
}

audit_verify() { # <log file>; exit status of scripts/audit-verify.sh
  DLAP_COMMON_DB_URL=dlap_common DLAP_PSQL="docker exec -i $PGC psql -U dlap" bash "$REPO_ROOT/scripts/audit-verify.sh" > "$1" 2>&1
}

# --- Keycloak helpers -------------------------------------------------------------------------------

json_get() { sed -n "s/.*\"$1\" *: *\"\\([^\"]*\\)\".*/\\1/p" | head -n 1; }
kc_token() { # <realm> <client> <username> <password> [client secret]  ->  access token
  local realm="$1" client="$2" user="$3" pass="$4" secret="${5:-}" body
  body="client_id=$client&grant_type=password&username=$user&password=$pass"
  [ -z "$secret" ] || body="$body&client_secret=$secret"
  printf '%s' "$body" | curl -s -X POST "$KC_URL/realms/$realm/protocol/openid-connect/token" -d @- | json_get access_token
}
# authfile <token> <file>: a curl header file, so a token never appears on a command line.
authfile() { printf 'Authorization: Bearer %s\n' "$1" > "$2"; }
http() { # <method> <url> <header file> [json body]  ->  prints "<status> <body file>"
  local m="$1" url="$2" hf="$3" body="${4:-}" out="$WORK/http.$RANDOM.json" code
  if [ -n "$body" ]; then
    code="$(printf '%s' "$body" | curl -s -o "$(hostpath "$out")" -w '%{http_code}' -X "$m" -H "@$(hostpath "$hf")" -H 'Content-Type: application/json' --data-binary @- "$url" || true)"
  else
    code="$(curl -s -o "$(hostpath "$out")" -w '%{http_code}' -X "$m" -H "@$(hostpath "$hf")" "$url" || true)"
  fi
  echo "$code $out"
}
kc_sa_token() { # a client-credentials token of the dlap-backend service account (ROLE_SERVICE_ACCOUNT)
  printf 'client_id=dlap-backend&grant_type=client_credentials&client_secret=%s' "$CLIENT_SECRET"     | curl -s -X POST "$KC_URL/realms/dlap/protocol/openid-connect/token" -d @- | json_get access_token
}
kc_admin_hf() { authfile "$(kc_token master admin-cli "$KC_ADMIN_USER" "$KC_ADMIN_PASS")" "$WORK/kc-admin.hdr"; }
kc_user_id() { # <realm user name>
  local r
  r="$(http GET "$KC_URL/admin/realms/dlap/users?username=$1&exact=true" "$WORK/kc-admin.hdr")"
  grep -o '"id" *: *"[^"]*"' "${r#* }" | head -n 1 | cut -d'"' -f4
}
kc_set_password() { # <user> <password>
  local id r
  id="$(kc_user_id "$1")"
  [ -n "$id" ] || return 1
  r="$(http PUT "$KC_URL/admin/realms/dlap/users/$id/reset-password" "$WORK/kc-admin.hdr" "{\"type\":\"password\",\"value\":\"$2\",\"temporary\":false}")"
  [ "${r%% *}" = 204 ]
}

# --- Waits ----------------------------------------------------------------------------------------------

HEALTH_TIMEOUT="${DLAP_DRILL_HEALTH_TIMEOUT:-420}"
service_up() { # <service>: /actuator/health is 200 and says UP
  local code
  rm -f "$WORK/health.body"
  code="$(curl -s -o "$(hostpath "$WORK/health.body")" -w '%{http_code}' "http://localhost:${PORT[$1]}/actuator/health" || true)"
  [ "$code" = 200 ] && [ -f "$WORK/health.body" ] && grep -q '"status" *: *"UP"' "$WORK/health.body"
}
wait_services() { # prints seconds until every service answers; returns 1 on timeout
  local t0 s waited
  t0="$(now)"
  for s in "${APP_SERVICES[@]}"; do
    waited=0
    until service_up "$s"; do
      waited=$(( $(now) - t0 ))
      [ "$waited" -le "$HEALTH_TIMEOUT" ] || { say "timeout: $s does not answer /actuator/health with 200 UP" >&2; return 1; }
      sleep 3
    done
  done
  echo $(( $(now) - t0 ))
}
wait_keycloak_realm() {
  wait_for 240 curl -sf "$KC_URL/realms/dlap/.well-known/openid-configuration"
}

# --- Markers ------------------------------------------------------------------------------------------
# One row a second, committed one by one, from a session of its own: the stand-in for continuous writes.
# The newest marker a restored stack holds, against the newest the lost stack wrote, is the RPO.

marker_sessions() { scalar postgres "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'drill-marker'"; }
marker_sessions_ge() { [ "$(marker_sessions)" -ge "$1" ]; }
marker_sessions_eq() { [ "$(marker_sessions)" -eq "$1" ]; }
markers_start() {
  docker exec -d -e PGAPPNAME=drill-marker "$PGC" psql -X -q -U dlap -d dlap_drill \
    -c 'DO $$ BEGIN FOR i IN 1..100000 LOOP INSERT INTO public.markers DEFAULT VALUES; COMMIT; PERFORM pg_sleep(1); END LOOP; END $$' > /dev/null
  wait_for 20 marker_sessions_ge 1
}
markers_stop() {
  pg -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = 'drill-marker'" > /dev/null
  wait_for 20 marker_sessions_eq 0
}
markers_last() { # prints "<n> <epoch seconds with milliseconds>"
  scalar dlap_drill "SELECT coalesce(max(n), 0) || ' ' || coalesce(round(extract(epoch FROM max(ts))::numeric, 3), 0) FROM public.markers" | tr '|' ' '
}

# --- Audit seeding ------------------------------------------------------------------------------------
# Streams with a running writer (govern.approval) get real entries from the API. The others have no writer
# in the quickstart yet, so the drill appends through the same trigger the writers use, as the stream's
# writer role: the insert is only accepted if it extends the head with the right hash, which is what makes
# these entries a fair test of the restore.

audit_seed() { # <stream> <writer role> <count> <tag>
  local stream="$1" role="$2" count="$3" tag="$4" i rec ts
  for i in $(seq 1 "$count"); do
    ts="$(date -u +%Y-%m-%dT%H:%M:%S).000000Z"
    rec="{\"actor\":{\"id\":\"restore-drill\",\"kind\":\"SYSTEM\"},\"details\":{\"seed\":\"$tag\",\"n\":$i},\"eventType\":\"drill.seed.entry\",\"occurredAt\":\"$ts\",\"outcome\":\"SUCCEEDED\",\"recordedAt\":\"$ts\",\"schema\":\"dlap-audit-record/1\"}"
    pg -d dlap_common > /dev/null <<SQL
SET ROLE $role;
INSERT INTO dlap_common.audit_log (stream_id, seq, prev_hash, entry_hash, record)
SELECT '$stream', n.seq, n.prev, dlap_common.audit_entry_hash('$stream', n.seq, n.prev, '$rec'), '$rec'
  FROM (SELECT coalesce(max(l.seq), 0) + 1 AS seq,
               coalesce((SELECT h.entry_hash FROM dlap_common.audit_log h WHERE h.stream_id = '$stream' ORDER BY h.seq DESC LIMIT 1), repeat('0', 64)) AS prev
          FROM dlap_common.audit_log l WHERE l.stream_id = '$stream') n;
SQL
  done
}
audit_seed_all() { # <tag> <count per stream>
  audit_seed retire.delete da_autodelete_user "$2" "$1"
  audit_seed retire.archive da_autoarchival_user "$2" "$1"
  audit_seed govern.policy da_policyengine_user "$2" "$1"
  audit_seed admin.access da_admin_user "$2" "$1"
  audit_seed report.export da_reporting_user "$2" "$1"
}

# --- Cleanup ------------------------------------------------------------------------------------------

cleanup() {
  local rc=$?
  if [ "$rc" -ne 0 ] || [ "$CHECKS_FAILED" -gt 0 ]; then
    local c
    for c in $(docker ps -a --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Names}}' 2> /dev/null | tr -d '\r'); do
      docker logs "$c" > "$OUT/log-$c.txt" 2>&1 || true
    done
  fi
  if [ "$KEEP" -eq 0 ]; then
    dc down -v --remove-orphans > /dev/null 2>&1 || true
    if [ "$rc" -ne 0 ] || [ "$CHECKS_FAILED" -gt 0 ]; then
      # A failed drill keeps the off-host store for the post-mortem; remove it with docker volume rm.
      docker volume rm "$REMOTE_B" "$REMOTE_C" "$KC_IMPORT" "$KC_STAGE" > /dev/null 2>&1 || true
      say "the off-host volume $REMOTE_A is kept for inspection (docker volume rm $REMOTE_A when done)"
    else
      docker volume rm "$REMOTE_A" "$REMOTE_B" "$REMOTE_C" "$KC_IMPORT" "$KC_STAGE" > /dev/null 2>&1 || true
    fi
    # The plain secrets of the run are not evidence.
    rm -f "$ENVF" "$PASSFILE" "$WORK"/*.hdr
  else
    say "--keep: project $PROJECT and volumes $REMOTE_A $REMOTE_B $REMOTE_C $KC_IMPORT $KC_STAGE are left in place; secrets are in $WORK"
  fi
  if [ "$rc" -ne 0 ] && [ "$CHECKS_FAILED" -eq 0 ]; then say "stopped early with status $rc; evidence in $OUT" >&2; fi
  harness_lock_release
}
trap cleanup EXIT

# ======================================================================================================
say "evidence goes to $OUT; compose project $PROJECT; off-host volume $REMOTE_A"
docker image inspect "$IMAGE" > /dev/null 2>&1 || docker pull "$IMAGE" > /dev/null
for v in "$REMOTE_A" "$REMOTE_B" "$REMOTE_C" "$KC_IMPORT"; do docker volume create "$v" > /dev/null; done
# The off-host stores stand in for a bucket the agent (uid 999) may write to.
for v in "$REMOTE_A" "$REMOTE_B" "$REMOTE_C"; do
  docker run --rm -v "$v:/remote" --entrypoint bash "$IMAGE" -c 'chown 999:999 /remote && chmod 700 /remote'
done
T_START="$(now)"

# --- Jars ---------------------------------------------------------------------------------------------
missing=0
for s in "${APP_SERVICES[@]}"; do [ -f "$REPO_ROOT/$s/build/libs/$s.jar" ] || missing=1; done
if [ "$REBUILD" -eq 1 ] || [ "$missing" -eq 1 ]; then
  say "building the service jars (Gradle, JDK 21)"
  t0="$(now)"
  tasks=()
  for s in "${APP_SERVICES[@]}"; do tasks+=(":$s:bootJar"); done
  "$REPO_ROOT/gradlew" -p "$REPO_ROOT" "${tasks[@]}" --no-daemon --console=plain --no-watch-fs --max-workers=2 \
    > "$OUT/gradle-bootjar.log" 2>&1 9>&- || { tail -n 30 "$OUT/gradle-bootjar.log" >&2; refuse "the jar build failed (see $OUT/gradle-bootjar.log)"; }
  record JAR_BUILD_SECONDS "$(( $(now) - t0 ))"
fi

# --- 1. Stack A ---------------------------------------------------------------------------------------
say "stack A: clean slate (evicts only what the harness rules allow)"
harness_reset_stack
t0="$(now)"
dc up -d --wait --wait-timeout 300 postgresql kafka keycloak > "$WORK/up-a-base.log" 2>&1 || { tail -n 30 "$WORK/up-a-base.log" >&2; fail "PostgreSQL, Kafka and Keycloak of stack A did not become healthy"; exit 1; }
record STACK_A_BASE_SECONDS "$(( $(now) - t0 ))"
dc up -d --wait --wait-timeout 300 dlap-backup > "$WORK/up-a-agent.log" 2>&1 || { tail -n 30 "$WORK/up-a-agent.log" >&2; fail "the backup agent of stack A did not start"; exit 1; }
t0="$(now)"
dc up -d --build --no-deps "${APP_SERVICES[@]}" > "$WORK/up-a-apps.log" 2>&1 || { tail -n 30 "$WORK/up-a-apps.log" >&2; fail "the services of stack A did not start"; exit 1; }
record IMAGE_BUILD_AND_CREATE_SECONDS "$(( $(now) - t0 ))"
if s="$(wait_services)"; then
  pass "stack A: all ${#APP_SERVICES[@]} services answer /actuator/health with 200 UP after $s s"
  record STACK_A_SERVICES_SECONDS "$s"
else
  fail "stack A: the services did not all answer within ${HEALTH_TIMEOUT}s"
  exit 1
fi
wait_keycloak_realm || { fail "the realm dlap is not served by Keycloak"; exit 1; }

# --- 2. Seed ------------------------------------------------------------------------------------------
say "seeding: fixture events through the log spine, users, approvals, audit entries, markers"
EXPECT_ASSETS=11
fixture_assets() { scalar dlap_common "SELECT count(*) FROM dlap_common.dlap_assets WHERE asset_uri LIKE 'gs://dlap-gcp-e2e%' OR asset_uri LIKE 's3://dlap-aws-e2e%' OR asset_uri LIKE '%dlapazuree2e%' OR asset_uri LIKE 'az://dlapazuree2e%' OR asset_uri LIKE 'bq://dlap-gcp-e2e/%' OR asset_uri LIKE 'projects/dlap-gcp-e2e/%'"; }
assets_ready() { [ "$(fixture_assets)" -ge "$EXPECT_ASSETS" ]; }
if wait_for 300 assets_ready; then
  pass "the replayed fixture events reached the catalog through Kafka ($(scalar dlap_common 'SELECT count(*) FROM dlap_common.dlap_assets') assets in dlap_common.dlap_assets)"
else
  fail "fewer than $EXPECT_ASSETS replayed assets reached the catalog within 300 s"
fi
kc_admin_hf
for u in admin cdo director; do
  case "$u" in admin) p="$LOGIN_PASS" ;; cdo) p="$LOGIN_PASS_2" ;; *) p="$LOGIN_PASS_3" ;; esac
  expect "Keycloak: the password of realm user $u is set at run time (state that is not in git)" kc_set_password "$u" "$p"
done
# A client with a run-time secret check: the dlap-backend token endpoint accepts the run's secret.
admin_token="$(kc_token dlap dlap-backend admin "$LOGIN_PASS" "$CLIENT_SECRET")"
[ -n "$admin_token" ] && pass "login: realm user admin gets a token from the dlap-backend client with the run's secret" || fail "login: no token for realm user admin"
cdo_token="$(kc_token dlap dlap-backend cdo "$LOGIN_PASS_2" "$CLIENT_SECRET")"
dir_token="$(kc_token dlap dlap-backend director "$LOGIN_PASS_3" "$CLIENT_SECRET")"
authfile "$admin_token" "$WORK/admin.hdr"
authfile "$cdo_token" "$WORK/cdo.hdr"
authfile "$dir_token" "$WORK/director.hdr"

# Approvals through the real API: one hard delete approved by two people, one left pending.
ASSET_ID="$(scalar dlap_common "SELECT asset_id FROM dlap_common.dlap_assets WHERE asset_uri LIKE 'gs://dlap-gcp-e2e%' ORDER BY asset_uri LIMIT 1")"
ASSET_URI="$(scalar dlap_common "SELECT asset_uri FROM dlap_common.dlap_assets WHERE asset_id = '$ASSET_ID'")"
mk_approval() { # <idempotency key> -> prints the approval id
  local body r
  body="{\"actionClass\":\"HARD_DELETE\",\"actionType\":\"delete\",\"assetId\":\"$ASSET_ID\",\"assetUri\":\"$ASSET_URI\",\"provider\":\"GCP\",\"serviceType\":\"GCS_BUCKET\",\"actionParameters\":{\"hardDelete\":true},\"reason\":\"restore drill: an approval that must survive the loss of the stack\",\"requiredApprovals\":2,\"idempotencyKey\":\"$1\"}"
  r="$(http POST "http://localhost:${PORT[da-policyengine]}/api/v1/policyengine/approvals" "$WORK/admin.hdr" "$body")"
  [ "${r%% *}" = 201 ] || { say "approval create answered ${r%% *}: $(head -c 300 "${r#* }")" >&2; return 1; }
  grep -o '"id" *: *"[^"]*"' "${r#* }" | head -n 1 | cut -d'"' -f4
}
APPROVAL_1="$(mk_approval "drill-approval-1" || true)"
APPROVAL_2="$(mk_approval "drill-approval-2" || true)"
if [ -n "$APPROVAL_1" ] && [ -n "$APPROVAL_2" ]; then
  pass "two approval requests were created through the policy engine API ($APPROVAL_1, $APPROVAL_2)"
  for h in cdo director; do
    r="$(http POST "http://localhost:${PORT[da-policyengine]}/api/v1/policyengine/approvals/$APPROVAL_1/decisions" "$WORK/$h.hdr" '{"decision":"APPROVE","comment":"restore drill decision"}')"
    expect "approval 1 decided by $h (HTTP ${r%% *})" test "${r%% *}" = 200 -o "${r%% *}" = 201
  done
  r="$(http GET "http://localhost:${PORT[da-policyengine]}/api/v1/policyengine/approvals/$APPROVAL_1" "$WORK/admin.hdr")"
  expect "approval 1 is APPROVED after two distinct approvers" grep -q '"status" *: *"APPROVED"' "${r#* }"
else
  fail "could not create the seed approvals (see above)"
fi
# The only approval path that writes the govern.approval stream at this release is the service-account withdraw
# (docs/specs/approvals.md rule 11); decisions are chained from AUD-02a on. Approval 2 stays PENDING until then.
if [ -n "$APPROVAL_2" ]; then
  ADMIN_SUB="$(kc_user_id admin)"
  authfile "$(kc_sa_token)" "$WORK/sa.hdr"
  r="$(http POST "http://localhost:${PORT[da-policyengine]}/api/v1/policyengine/approvals/$APPROVAL_2/withdraw" "$WORK/sa.hdr" "{\"reason\":\"restore drill: withdrawn on behalf of its author\",\"onBehalfOf\":\"$ADMIN_SUB\"}")"
  expect "approval 2 is withdrawn by the dlap-backend service account on behalf of its author (HTTP ${r%% *}); the policy engine writes govern.approval" test "${r%% *}" = 200
fi
audit_seed_all "seed" 3
record AUDIT_ENTRIES_SEEDED "$(scalar dlap_common 'SELECT count(*) FROM dlap_common.audit_log')"
expect "audit-verify passes on every stream of the seeded stack" audit_verify "$OUT/audit-verify-a.txt"
record AUDIT_STREAMS_WITH_ENTRIES "$(scalar dlap_common 'SELECT count(DISTINCT stream_id) FROM dlap_common.audit_log')"
expect "every one of the 6 registered audit streams has entries (govern.approval from the real service, the other five seeded)" test "$(scalar dlap_common 'SELECT count(DISTINCT stream_id) FROM dlap_common.audit_log')" -eq 6
record AUDIT_ENTRIES_GOVERN_APPROVAL "$(scalar dlap_common "SELECT count(*) FROM dlap_common.audit_log WHERE stream_id = 'govern.approval'")"

if [ "$ROWS" -gt 0 ]; then
  docker exec -i "$PGC" psql -X -q -v ON_ERROR_STOP=1 -U da_logingest_user -d da_logingest > /dev/null <<SQL
SET search_path TO dlap_logingest, public;
INSERT INTO log_events (event_id, event_time, ingest_time, source_provider, source_service, change_type, operation, result_status, principal_id, principal_type, target_resource_id, target_resource_kind, target_project, target_region, schema_version, raw_event, binding_id, native_event_id)
SELECT 'drill-' || g, clock_timestamp(), clock_timestamp(), 'GCP', 'storage.googleapis.com', 'DATA_ACCESS', 'storage.objects.get', 'SUCCESS',
       'principal-' || (g % 50), 'USER', 'bucket-' || (g % 20) || '/object-' || g, 'GCS_OBJECT', 'drill-project', 'us-central1', '1',
       jsonb_build_object('g', g, 'pad', md5(random()::text) || md5(random()::text) || md5(random()::text) || md5(random()::text) || md5(random()::text) || md5(random()::text) || md5(random()::text) || md5(random()::text)), 'drill-binding', 'native-' || g
  FROM generate_series(1, $ROWS) g;
SQL
  pass "$ROWS synthetic log events were added to dlap_logingest.log_events"
fi
pg -d postgres -c "CREATE DATABASE dlap_drill" > /dev/null
pgdb dlap_drill -c "CREATE TABLE public.markers (n bigserial PRIMARY KEY, ts timestamptz NOT NULL DEFAULT clock_timestamp(), note text NOT NULL DEFAULT 'm')" > /dev/null
markers_start
sleep 5
record SEED_SECONDS "$(( $(now) - T_START ))"
[ "$STOP_AFTER" != seed ] || { say "stopping after seed as asked"; [ "$CHECKS_FAILED" -eq 0 ]; exit $?; }

# --- 3. Protect ---------------------------------------------------------------------------------------
say "protect: stopping the services so the live database can be compared exactly with the restored one"
markers_stop
dc stop -t 30 "${APP_SERVICES[@]}" > "$WORK/stop-apps.log" 2>&1 || true
expect "no DLAP service is running while the backup is taken" test -z "$(dc ps --status running --services 2> /dev/null | grep '^da-' || true)"
digest_to "$OUT/digest-backup-point.tsv"
flyway_history > "$OUT/flyway-backup-point.txt"
record TABLES_AT_BACKUP "$(wc -l < "$OUT/digest-backup-point.tsv" | tr -d ' ')"
record ROWS_AT_BACKUP "$(awk -F'\t' '{ s += $3 } END { printf "%.0f", s }' "$OUT/digest-backup-point.tsv")"
record DATABASES_AT_BACKUP "$(cut -f1 "$OUT/digest-backup-point.tsv" | sort -u | wc -l | tr -d ' ')"
record DATABASE_BYTES_AT_BACKUP "$(scalar postgres 'SELECT sum(pg_database_size(datname)) FROM pg_database')"
read -r MARKER_N_AT_BACKUP _ <<< "$(markers_last)"

# The realm goes through the scheduled wrapper (DR-03), the way cron or the systemd timer of the guide runs it:
# keycloak-realm-backup.sh exports into a staging volume and hands the encrypted files to a ship command on the
# host. Here the ship command copies into the off-host volume, which is what a bucket is in this drill.
KC_STATE="$WORK/kc-backup-state"
cat > "$WORK/kc-ship.sh" << 'SHIP'
#!/usr/bin/env bash
# The off-host command of the drill: $1 a file on the host, $2 its key. The off-host volume is KC_SHIP_VOLUME.
export MSYS_NO_PATHCONV=1
f="$1"
if command -v cygpath > /dev/null 2>&1; then f="$(cygpath -m "$1")"; fi
docker run --rm -v "$KC_SHIP_VOLUME:/remote" -v "$f:/in:ro" --entrypoint bash "$KC_SHIP_IMAGE" -c 'mkdir -p "/remote/$(dirname "$1")" && cp /in "/remote/$1"' _ "$2"
SHIP
KC_STAMP=""
t0="$(now)"
if KC_SHIP_VOLUME="$REMOTE_A" KC_SHIP_IMAGE="$IMAGE" DLAP_KC_STATE_DIR="$KC_STATE" DLAP_KC_BACKUP_VOLUME="$KC_STAGE" DLAP_KC_CONTAINER=dlap-keycloak DLAP_KC_REALM=dlap \
     DLAP_BACKUP_PASSPHRASE_FILE="$PASSFILE" DLAP_DR_IMAGE="$IMAGE" DLAP_BACKUP_SHIP_CMD='bash "'"$(hostpath "$WORK")"'/kc-ship.sh" "$1" "$2"' \
     bash "$SCRIPT_DIR/keycloak-realm-backup.sh" run > "$OUT/keycloak-backup.log" 2>&1; then
  KC_STAMP="$(sed -n 's/^LAST_SET=//p' "$KC_STATE/status.env" | tr -d '\r')"
  pass "the Keycloak realm dlap was exported, encrypted and shipped off the host by the scheduled wrapper (keycloak/$KC_STAMP)"
else
  fail "keycloak-realm-backup.sh run failed"
  tail -n 20 "$OUT/keycloak-backup.log" >&2
fi
KC_KEY="keycloak/$KC_STAMP"
expect "the wrapper's status says ok and shipped, and its check passes" bash -c "grep -q '^LAST_RESULT=ok\$' '$KC_STATE/status.env' && grep -q '^SHIPPED=yes\$' '$KC_STATE/status.env' && DLAP_KC_STATE_DIR='$KC_STATE' bash '$SCRIPT_DIR/keycloak-realm-backup.sh' check"
expect "the realm set is complete on the off-host volume (encrypted realm, manifest.env and SHA256SUMS.shipped)" docker run --rm -v "$REMOTE_A:/v:ro" --entrypoint bash "$IMAGE" -c "test -s '/v/$KC_KEY/dlap-realm.json.enc' && test -f '/v/$KC_KEY/manifest.env' && test -f '/v/$KC_KEY/SHA256SUMS.shipped'"
record KEYCLOAK_BACKUP_SECONDS "$(( $(now) - t0 ))"
record KEYCLOAK_REALM_PLAIN_BYTES "$(sed -n 's/^PLAIN_BYTES=//p' <(docker run --rm -v "$REMOTE_A:/v:ro" --entrypoint cat "$IMAGE" "/v/$KC_KEY/manifest.env") | tr -d '\r')"

t0="$(now)"
dc exec -T dlap-backup bash /opt/dr/agent.sh once logical > "$OUT/logical-backup.log" 2>&1 || { fail "agent.sh once logical failed"; tail -n 20 "$OUT/logical-backup.log" >&2; }
record LOGICAL_BACKUP_SECONDS "$(( $(now) - t0 ))"
T_BACKUP_END="$(now)"
LOGICAL_SET="$(dc exec -T dlap-backup bash -c "grep '^LAST_SUCCESS_SET=' /backups/status/logical.env | cut -d= -f2" | tr -d '\r')"
record LOGICAL_SET "$LOGICAL_SET"
record LOGICAL_DUMP_BYTES "$(dc exec -T dlap-backup bash -c "grep '^DUMP_BYTES=' /backups/logical/$LOGICAL_SET/manifest.env | cut -d= -f2" | tr -d '\r')"
expect "the logical set is complete off the host (SHA256SUMS.shipped is there)" docker run --rm -v "$REMOTE_A:/v:ro" --entrypoint test "$IMAGE" -f "/v/logical/$LOGICAL_SET/SHA256SUMS.shipped"
expect "nothing on the off-host volume is plain (no *.dump, no realm json)" docker run --rm -v "$REMOTE_A:/v:ro" --entrypoint bash "$IMAGE" -c '! find /v -type f \( -name "*.dump" -o -name "*-realm.json" -o -name "*.tar.gz" \) | grep -q .'
SET_DATABASES="$(docker run --rm -v "$REMOTE_A:/v:ro" --entrypoint bash "$IMAGE" -c "ls /v/logical/$LOGICAL_SET | grep -c '\.dump\.enc$'" | tr -d '\r')"
record DATABASES_IN_LOGICAL_SET "$SET_DATABASES"
expect "the logical set holds a dump of every database ($SET_DATABASES dumps for $(cut -f1 "$OUT/digest-backup-point.tsv" | sort -u | wc -l | tr -d ' ') databases with tables)" test "$SET_DATABASES" -ge "$(cut -f1 "$OUT/digest-backup-point.tsv" | sort -u | wc -l | tr -d ' ')"

# A change in Keycloak after the export: the loss window of the realm backup, made visible.
kc_admin_hf
r="$(http POST "$KC_URL/admin/realms/dlap/users" "$WORK/kc-admin.hdr" '{"username":"drill-after-backup","enabled":true}')"
expect "a Keycloak user is created after the realm export (it must be absent after the restore)" test "${r%% *}" = 201
# Writes go on after the backup: markers, and audit entries that only the WAL can bring back.
markers_start
sleep 20
markers_stop
audit_seed_all "after-backup" 2
T_AUDIT_AFTER="$(now)"
markers_start
digest_to "$OUT/digest-final.tsv" --databases "$(awk -F'\t' '{ print $1 }' "$OUT/digest-backup-point.tsv" | sort -u | grep -v '^dlap_drill$' | tr '\n' ',' | sed 's/,$//')"
flyway_history > "$OUT/flyway-final.txt"
record AUDIT_ENTRIES_AT_DISASTER "$(scalar dlap_common 'SELECT count(*) FROM dlap_common.audit_log')"
say "waiting until the agent has copied the WAL up to the last audit entry off the host (cycle 30 s)"
shipped_past() { # <epoch>
  [ "$(dc exec -T dlap-backup bash -c "grep '^WAL_SHIPPED_UPTO_EPOCH=' /backups/status/ship-wal.env | cut -d= -f2" | tr -d '\r')" -gt "$1" ]
}
if wait_for 300 shipped_past "$T_AUDIT_AFTER"; then pass "the off-host WAL copy covers the audit entries written after the backup"; else fail "the WAL was not copied off the host within 300 s"; fi
record DATABASE_BYTES_AT_DISASTER "$(scalar postgres 'SELECT sum(pg_database_size(datname)) FROM pg_database')"
docker run --rm -v "$REMOTE_A:/v:ro" --entrypoint bash "$IMAGE" -c 'cd /v && find . -type f -printf "%s %p
" | sort -k2' > "$OUT/offhost-listing.txt" 2>&1 || true
record OFFHOST_BYTES "$(awk '{ s += $1 } END { printf "%.0f", s }' "$OUT/offhost-listing.txt")"
[ "$STOP_AFTER" != protect ] || { say "stopping after protect as asked"; [ "$CHECKS_FAILED" -eq 0 ]; exit $?; }

# --- 4. Disaster --------------------------------------------------------------------------------------
# The marker session is still writing. Read what the lost stack wrote last, then remove everything.
read -r LIVE_N LIVE_TS <<< "$(markers_last)"
T_DISASTER="$(now)"
say "DISASTER: removing the stack and all of its volumes (last marker written: $LIVE_N)"
dc down -v --remove-orphans > "$WORK/down-a.log" 2>&1 || true
remaining="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT" | wc -l | tr -d ' ')"
expect "the stack, its containers and its volumes are gone ($remaining volumes of $PROJECT remain)" test "$remaining" = 0
expect "the off-host volume survived" docker volume inspect "$REMOTE_A"
record MARKERS_WRITTEN_BEFORE_DISASTER "$LIVE_N"
record SECONDS_FROM_BACKUP_END_TO_DISASTER "$((T_DISASTER - T_BACKUP_END))"

# ======================================================================================================
# Both restores use the functions below. verify_restored runs BEFORE the services start (they write at start),
# verify_running after. T_RECOVERY is the moment the recovery began: the stack was gone.
rto_mark() { record "RTO_${1}_$2" "$(( $(now) - T_RECOVERY ))"; }

# After a point-in-time restore: did recovery replay through the newest segment the restore placed in pg_wal?
# "redo done at H/L" is where WAL ended; segment number = H * 256 + L / 16 MiB (16 MiB segments). A recovery
# that stops earlier replayed nothing of the archive beyond that point although restore-pitr.sh said it was ready.
replay_reached_archive_end() {
  local redo hi lo last seg_hi seg_lo redo_seg last_seg
  redo="$(sed -n 's/.*redo done at \([0-9A-F]*\)\/\([0-9A-F]*\) .*/\1 \2/p' "$OUT/postgres-pitr.log" | tail -n 1)"
  last="$(sed -n 's/.*WAL: [0-9]* segment(s) from [0-9A-F]* to \([0-9A-F]\{24\}\).*/\1/p' "$OUT/restore-pitr.log" | tail -n 1)"
  if [ -z "$redo" ] || [ -z "$last" ]; then fail "pitr: could not read where recovery ended or which segment was last (see postgres-pitr.log, restore-pitr.log)"; return 1; fi
  read -r hi lo <<< "$redo"
  seg_hi=$((16#${last:8:8}))
  seg_lo=$((16#${last:16:8}))
  redo_seg=$(( 16#$hi * 256 + 16#$lo / 16777216 ))
  last_seg=$(( seg_hi * 256 + seg_lo ))
  record PITR_REDO_DONE_AT "$hi/$lo"
  record PITR_NEWEST_SEGMENT "$last"
  if [ "$redo_seg" -ge "$last_seg" ]; then pass "pitr: recovery replayed through the newest archived segment (WAL ended at $hi/$lo, segment $last)"; else fail "pitr: recovery ended at $hi/$lo, before the newest archived segment $last: the archive beyond that point was not applied"; return 1; fi
}

verify_restored() { # <label> <what the marker table must hold: logical|pitr>
  local label="$1" kind="$2" rn rts
  # Row counts and digests BEFORE the services start: the services write at start.
  if [ "$kind" = logical ]; then
    digest_to "$OUT/digest-$label.tsv"
    if diff "$OUT/digest-backup-point.tsv" "$OUT/digest-$label.tsv" > "$OUT/diff-$label.txt" 2>&1; then
      pass "$label: all $(wc -l < "$OUT/digest-$label.tsv" | tr -d ' ') tables ($(awk -F'\t' '{ s += $3 } END { printf "%.0f", s }' "$OUT/digest-$label.tsv") rows) have the same row count and content digest as the live database at the backup"
    else
      fail "$label: the restored tables differ from the live database at the backup (see $OUT/diff-$label.txt)"
      head -n 20 "$OUT/diff-$label.txt" >&2
    fi
  else
    digest_to "$OUT/digest-$label.tsv" --databases "$(awk -F'\t' '{ print $1 }' "$OUT/digest-final.tsv" | sort -u | tr '\n' ',' | sed 's/,$//')"
    if diff "$OUT/digest-final.tsv" "$OUT/digest-$label.tsv" > "$OUT/diff-$label.txt" 2>&1; then
      pass "$label: all $(wc -l < "$OUT/digest-$label.tsv" | tr -d ' ') tables of the service databases ($(awk -F'\t' '{ s += $3 } END { printf "%.0f", s }' "$OUT/digest-$label.tsv") rows) equal the live state at the disaster, including the audit entries written after the logical backup"
    else
      fail "$label: the restored tables differ from the live database at the disaster (see $OUT/diff-$label.txt)"
      head -n 20 "$OUT/diff-$label.txt" >&2
    fi
  fi
  flyway_history > "$OUT/flyway-$label-before-start.txt"
  if [ "$kind" = logical ]; then
    expect "$label: the Flyway history of every service is identical to the one at the backup ($(wc -l < "$OUT/flyway-backup-point.txt" | tr -d ' ') rows)" diff "$OUT/flyway-backup-point.txt" "$OUT/flyway-$label-before-start.txt"
  else
    expect "$label: the Flyway history of every service is identical to the one at the disaster ($(wc -l < "$OUT/flyway-final.txt" | tr -d ' ') rows)" diff "$OUT/flyway-final.txt" "$OUT/flyway-$label-before-start.txt"
  fi
  rto_mark "$label" DATA_VERIFIED
  # The marker table: where does the restored stack stand?
  read -r rn rts <<< "$(markers_last)"
  rn="${rn:-0}"
  rts="${rts:-0}"
  if [ "$kind" = logical ]; then
    expect "$label: the marker table equals the backup point (newest marker $rn, written before the backup)" test "$rn" = "$MARKER_N_AT_BACKUP"
  fi
  record "RPO_${label}_NEWEST_MARKER_RESTORED" "$rn"
  record "RPO_${label}_MARKERS_LOST" "$((LIVE_N - rn))"
  record "RPO_${label}_SECONDS_LOST" "$(awk -v a="$LIVE_TS" -v b="$rts" 'BEGIN { printf "%.1f", a - b }')"
  say "$label: the restored stack stands at marker $rn; the lost stack wrote $LIVE_N, so $((LIVE_N - rn)) markers ($(awk -v a="$LIVE_TS" -v b="$rts" 'BEGIN { printf "%.1f", a - b }') s of writes) are lost"
}

# start_services <label>: start Kafka, the agent and the ten services on the restored data; measure.
start_services() { # <label>
  local s
  dc up -d --no-deps --wait --wait-timeout 300 kafka > "$WORK/up-$1-kafka.log" 2>&1 || fail "$1: Kafka did not become healthy"
  dc up -d --wait --wait-timeout 300 dlap-backup > "$WORK/up-$1-agent.log" 2>&1 || fail "$1: the backup agent did not start"
  export DLAP_LOGINGEST_REPLAY_ENABLED=false
  dc up -d --no-deps "${APP_SERVICES[@]}" > "$WORK/up-$1-apps.log" 2>&1 || { tail -n 20 "$WORK/up-$1-apps.log" >&2; fail "$1: the services did not start"; return 1; }
  if s="$(wait_services)"; then
    pass "$1: all ${#APP_SERVICES[@]} services answer /actuator/health with 200 UP on the restored data (${s} s after they were started)"
    rto_mark "$1" SERVICES_HEALTHY
  else
    fail "$1: the services did not all answer /actuator/health within ${HEALTH_TIMEOUT}s"
    return 1
  fi
}

verify_running() { # <label> <expect the after-backup audit entries: yes|no>
  local label="$1" has_after="$2" tok r
  flyway_history > "$OUT/flyway-$label-after-start.txt"
  expect "$label: starting the services changed no Flyway history row and applied no migration" diff "$OUT/flyway-$label-before-start.txt" "$OUT/flyway-$label-after-start.txt"
  expect "$label: no Flyway migration failed (success is true in every history row)" bash -c "! grep -q '|f|' '$OUT/flyway-$label-after-start.txt'"
  if audit_verify "$OUT/audit-verify-$label.txt"; then
    pass "$label: audit-verify passes on every stream ($(grep -c '^OK' "$OUT/audit-verify-$label.txt") streams OK)"
  else
    fail "$label: audit-verify reports a broken stream (see $OUT/audit-verify-$label.txt)"
    head -n 12 "$OUT/audit-verify-$label.txt" >&2
  fi
  if [ "$has_after" = yes ]; then
    expect "$label: the chain holds the entries written after the logical backup ($(scalar dlap_common "SELECT count(*) FROM dlap_common.audit_log WHERE record LIKE '%after-backup%'") of 10)" test "$(scalar dlap_common "SELECT count(*) FROM dlap_common.audit_log WHERE record LIKE '%after-backup%'")" -eq 10
  else
    expect "$label: the chain stops at the backup, none of the 10 later entries is present" test "$(scalar dlap_common "SELECT count(*) FROM dlap_common.audit_log WHERE record LIKE '%after-backup%'")" -eq 0
  fi
  # Login with the password that was set at run time and exists only in the realm export.
  tok="$(kc_token dlap dlap-backend admin "$LOGIN_PASS" "$CLIENT_SECRET")"
  if [ -n "$tok" ]; then pass "$label: login works: realm user admin signs in with the password set before the disaster"; else fail "$label: login failed for realm user admin"; fi
  authfile "$tok" "$WORK/admin-$label.hdr"
  r="$(http GET "http://localhost:${PORT[da-catalog]}/api/v1/catalog/assets?size=1" "$WORK/admin-$label.hdr")"
  expect "$label: the token is accepted by the catalog (HTTP ${r%% *}) and the asset list is served" test "${r%% *}" = 200
  r="$(http GET "http://localhost:${PORT[da-policyengine]}/api/v1/policyengine/approvals/$APPROVAL_1" "$WORK/admin-$label.hdr")"
  expect "$label: the policy engine returns the seeded approval as APPROVED (HTTP ${r%% *})" bash -c "[ '${r%% *}' = 200 ] && grep -q '\"status\" *: *\"APPROVED\"' '${r#* }'"
  r="$(http GET "http://localhost:${PORT[da-policyengine]}/api/v1/policyengine/approvals/$APPROVAL_2" "$WORK/admin-$label.hdr")"
  expect "$label: the withdrawn approval is WITHDRAWN (HTTP ${r%% *})" bash -c "[ '${r%% *}' = 200 ] && grep -q '\"status\" *: *\"WITHDRAWN\"' '${r#* }'"
  kc_admin_hf
  r="$(http GET "$KC_URL/admin/realms/dlap/users?username=drill-after-backup&exact=true" "$WORK/kc-admin.hdr")"
  expect "$label: the Keycloak user created after the realm export is absent (the realm stands at the export)" bash -c "[ '${r%% *}' = 200 ] && [ \"\$(cat '${r#* }')\" = '[ ]' -o \"\$(cat '${r#* }')\" = '[]' ]"
  r="$(http GET "$KC_URL/admin/realms/dlap/users?username=cdo&exact=true" "$WORK/kc-admin.hdr")"
  expect "$label: the realm users and roles of the export are there (user cdo found)" bash -c "grep -q '\"username\" *: *\"cdo\"' '${r#* }'"
  rto_mark "$label" ALL_VERIFIED
}

stack_down() { dc down -v --remove-orphans > "$WORK/down-$1.log" 2>&1 || true; }

restore_keycloak() { # <label>: realm export -> import volume -> Keycloak
  local t0
  t0="$(now)"
  KC_RESTORE=1
  DLAP_BACKUP_PASSPHRASE_FILE="$PASSFILE" bash "$SCRIPT_DIR/keycloak-realm.sh" restore --from-volume "$REMOTE_A" --key "$KC_KEY" --realm dlap --import-volume "$KC_IMPORT" > "$OUT/keycloak-restore-$1.log" 2>&1 \
    || { fail "$1: keycloak-realm.sh restore failed"; tail -n 20 "$OUT/keycloak-restore-$1.log" >&2; return 1; }
  dc up -d --no-deps --wait --wait-timeout 300 keycloak > "$WORK/up-$1-keycloak.log" 2>&1 || { fail "$1: Keycloak did not become healthy on the restored realm"; return 1; }
  wait_keycloak_realm || { fail "$1: the restored Keycloak does not serve the realm dlap"; return 1; }
  bash "$SCRIPT_DIR/keycloak-realm.sh" wipe --import-volume "$KC_IMPORT" > /dev/null 2>&1 || true
  rto_mark "$1" KEYCLOAK_UP
  record "KEYCLOAK_RESTORE_${1}_SECONDS" "$(( $(now) - t0 ))"
}

# --- 5. Stack B: logical restore ----------------------------------------------------------------------
if [ "$RESTORE" = logical ] || [ "$RESTORE" = both ]; then
  export DR_STACK_REMOTE="$REMOTE_B"
  T_RECOVERY="$(now)"
  say "stack B: logical restore. PostgreSQL from the init scripts, restore.sh from the off-host set $LOGICAL_SET"
  dc up -d --wait --wait-timeout 300 postgresql > "$WORK/up-b-pg.log" 2>&1 || { tail -n 20 "$WORK/up-b-pg.log" >&2; fail "PostgreSQL of stack B did not become healthy"; exit 1; }
  rto_mark logical POSTGRES_UP
  if agentrun /opt/dr/restore.sh --from "/source/logical/$LOGICAL_SET" --yes-replace-databases --work-dir /backups/restore-tmp > "$OUT/restore-logical.log" 2>&1; then
    pass "logical: restore.sh from the encrypted off-host set exited 0"
  else
    fail "logical: restore.sh failed (see $OUT/restore-logical.log)"
    tail -n 30 "$OUT/restore-logical.log" >&2
  fi
  expect "logical: restore.sh reported RESTORE VERIFIED (it recounted every table against the counts taken from the dumps)" grep -q 'RESTORE VERIFIED' "$OUT/restore-logical.log"
  record RESTORE_LOGICAL_SECONDS "$(sed -n 's/.*restored in \([0-9]*\)s.*/\1/p' "$OUT/restore-logical.log" | tail -n 1)"
  rto_mark logical DATABASES_RESTORED
  verify_restored logical logical
  restore_keycloak logical || true
  start_services logical && verify_running logical no || true
  record "RTO_logical_SECONDS" "$(( $(now) - T_RECOVERY ))"
  if [ "$RESTORE" = both ]; then stack_down b; fi
fi

# --- 6. Stack C: point-in-time restore ----------------------------------------------------------------
if [ "$RESTORE" = pitr ] || [ "$RESTORE" = both ]; then
  export DR_STACK_REMOTE="$REMOTE_C"
  KC_RESTORE=0
  T_RECOVERY="$(now)"
  say "stack C: point-in-time restore from the base backup and the WAL on the off-host volume"
  # up.sh passes create and start through; here Compose does the same: the volume must be filled before PostgreSQL starts.
  dc up --no-start postgresql > "$WORK/create-c.log" 2>&1 || { tail -n 20 "$WORK/create-c.log" >&2; fail "cannot create the database container of stack C"; exit 1; }
  t1="$(now)"
  if docker run --rm -v "${PROJECT}_postgresql-data:/pgdata" -v "$REMOTE_A:/remote:ro" -v "$(hostpath "$SCRIPT_DIR"):/opt/dr:ro" \
       -v "$(hostpath "$PASSFILE"):/run/secrets/dlap_backup_passphrase:ro" -v "$(hostpath "$WORK"):/report" --entrypoint bash "$IMAGE" \
       /opt/dr/restore-pitr.sh --from /remote --pgdata /pgdata --target latest --report /report/restore-pitr.report > "$OUT/restore-pitr.log" 2>&1; then
    pass "pitr: restore-pitr.sh to the latest shipped moment exited 0"
  else
    fail "pitr: restore-pitr.sh failed (see $OUT/restore-pitr.log)"
    tail -n 30 "$OUT/restore-pitr.log" >&2
  fi
  record PITR_PREPARE_SECONDS "$(( $(now) - t1 ))"
  # restore-pitr.sh reads the prepared WAL with pg_waldump before it reports ready (Q-752): the report says how that went.
  expect "pitr: restore-pitr.sh read the prepared WAL through the newest archived segment itself (REPLAY_CHECK=ok)" grep -q '^REPLAY_CHECK=ok$' "$WORK/restore-pitr.report"
  record PITR_REPLAY_CHECK "$(sed -n 's/^REPLAY_CHECK=//p' "$WORK/restore-pitr.report" | tr -d '\r')"
  record PITR_REPLAY_STOP_LSN "$(sed -n 's/^REPLAY_STOP_LSN=//p' "$WORK/restore-pitr.report" | tr -d '\r')"
  record PITR_REPLACED_BASE_SEGMENTS "$(sed -n 's/^REPLACED_BASE_SEGMENTS=//p' "$WORK/restore-pitr.report" | tr -d '\r')"
  record PITR_WAL_SEGMENTS "$(sed -n 's/.*WAL: \([0-9]*\) segment.*/\1/p' "$OUT/restore-pitr.log" | tail -n 1)"
  t1="$(now)"
  dc up -d --wait --wait-timeout 300 postgresql > "$WORK/up-c-pg.log" 2>&1 || { tail -n 20 "$WORK/up-c-pg.log" >&2; fail "PostgreSQL did not start on the restored data directory"; exit 1; }
  recovered() { [ "$(scalar postgres 'SELECT pg_is_in_recovery()')" = f ]; }
  wait_for 300 recovered || fail "pitr: recovery did not finish within 300 s"
  record PITR_RECOVERY_SECONDS "$(( $(now) - t1 ))"
  rto_mark pitr POSTGRES_UP
  rto_mark pitr DATABASES_RESTORED
  docker logs "$PGC" > "$OUT/postgres-pitr.log" 2>&1 || true
  replay_reached_archive_end || true
  verify_restored pitr pitr
  KC_RESTORE=1
  restore_keycloak pitr || true
  start_services pitr && verify_running pitr yes || true
  record "RTO_pitr_SECONDS" "$(( $(now) - T_RECOVERY ))"
fi

# --- Report -------------------------------------------------------------------------------------------
record TOTAL_SECONDS "$(( $(now) - T_START ))"
record CHECKS_PASSED "$CHECKS_PASSED"
record CHECKS_FAILED "$CHECKS_FAILED"
echo
echo "stack drill: $CHECKS_PASSED checks passed, $CHECKS_FAILED failed, $(( $(now) - T_START )) s in all"
echo "numbers (also in $RESULTS):"
grep -E '^(RTO_|RPO_|RESTORE_|PITR_|LOGICAL_|KEYCLOAK_|TABLES_|ROWS_|DATABASES_|DATABASE_BYTES|AUDIT_|MARKERS_|SECONDS_|STACK_A|IMAGE_|SEED_|JAR_|TOTAL_)' "$RESULTS" | sed 's/^/    /'
[ "$CHECKS_FAILED" -eq 0 ]
