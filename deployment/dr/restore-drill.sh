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
# Restore drill (DR-01): lose a stack, rebuild it from the off-host copy, compare the rows.
#
# It builds a pilot database tier from the real files (the quickstart pair, every
# docker-compose.pilot.*.yml including the backup overlay, the init scripts, the upgrade runner and
# the Flyway migrations of the services), fills it, lets the backup agent protect it, destroys it,
# and restores it into fresh stacks two ways. Everything is compared row by row, with a content digest
# of every table, and every step is timed.
#
#   1. Stack A: PostgreSQL and the backup agent. Baseline data, then the agent's own first logical and
#      base backups, then more data, a logical backup, more data, a named restore point, more data.
#      Wait until the WAL agent has copied it all to the "off-host" volume.
#   2. Disaster: stack A and all its volumes are removed. Only the off-host volume survives.
#   3. Stack B, logical restore: a fresh PostgreSQL from the init scripts, restore.sh from the newest
#      logical set on the off-host volume. Must equal the data at that backup.
#   4. Stack C, point-in-time restore, twice: restore-pitr.sh from the base backup and WAL on the
#      off-host volume, to the restore point (must equal the data then) and to the latest moment
#      (must equal the data at the disaster, including what was only in the segment being written).
#   5. In B and C the backup agent starts again on the restored data and must become healthy.
#
# The drill uses its own compose projects (dlap-drill-<id>-a/b/c), project-scoped container names, no
# published ports and a volume of its own for the off-host store, so it can run beside a live pilot.
# It needs about 2 GB of RAM and 1 GB of disk for the default size and takes about 10 minutes.
#
# Usage:  restore-drill.sh [options]
#   --rows N       synthetic log events in the first batch (default 20000; two more batches of N/2 and
#                  N/4 follow). Use 2000000 for an hour-long run with realistic volume.
#   --out DIR      keep the evidence here (counts, reports, the timing table); default a temp folder
#   --skip-pitr    only the logical restore
#   --keep         leave the three stacks and the off-host volume running/present for inspection
#   -h, --help     this text
# Needs: docker with Compose 2.24 or later, the postgres:15.1 image (pulled if absent), bash, openssl,
# keytool (JDK 11 or later, for the key material of the pilot TLS overlay: tls/gen-certs.sh).
# Exit: 0 every check passed; 1 a check failed (the evidence folder says which); 2 bad argument or a
# prerequisite is missing.

set -euo pipefail
export MSYS_NO_PATHCONV=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PILOT_DIR="$REPO_ROOT/deployment/environments/pilot"
UPGRADE_RUNNER="$REPO_ROOT/deployment/platform/database/upgrades/run-upgrades.sh"
INIT_SEED="$REPO_ROOT/deployment/platform/database/init-scripts/03-insert-test-data.sql"
IMAGE="postgres:15.1"

ROWS=20000
OUT=""
SKIP_PITR=0
KEEP=0

usage() { awk '/^# Restore drill/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }
refuse() { echo "restore-drill: $*" >&2; exit 2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --rows) [ $# -ge 2 ] || refuse "--rows needs a number"; ROWS="$2"; shift 2 ;;
    --out) [ $# -ge 2 ] || refuse "--out needs a folder"; OUT="$2"; shift 2 ;;
    --skip-pitr) SKIP_PITR=1; shift ;;
    --keep) KEEP=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) refuse "unknown argument: $1" ;;
  esac
done
[[ "$ROWS" =~ ^[0-9]+$ ]] && [ "$ROWS" -ge 100 ] || refuse "--rows must be a whole number of at least 100"

command -v docker > /dev/null 2>&1 || refuse "docker is not installed"
docker info > /dev/null 2>&1 || refuse "the Docker daemon does not answer"
command -v openssl > /dev/null 2>&1 || refuse "openssl is required (random secrets)"
command -v keytool > /dev/null 2>&1 || refuse "keytool is required (tls/gen-certs.sh of the pilot TLS overlay)"
compose_version="$(docker compose version --short 2> /dev/null || true)"
[ -n "$compose_version" ] || refuse "the Docker Compose plugin is missing"
IFS=. read -r cmaj cmin _ <<< "${compose_version#v}"
if [ "${cmaj:-0}" -lt 2 ] || { [ "${cmaj:-0}" -eq 2 ] && [ "${cmin:-0}" -lt 24 ]; }; then refuse "Docker Compose $compose_version is older than 2.24 (the pilot overlay needs !override)"; fi

hostpath() { if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
now() { date +%s; }
say() { printf '%s drill: %s\n' "$(date -u +%H:%M:%S)" "$*"; }

ID="$(date +%H%M%S)$((RANDOM % 900 + 100))"
PA="dlap-drill-$ID-a"
PB="dlap-drill-$ID-b"
PC="dlap-drill-$ID-c"
REMOTE="dlap-drill-$ID-remote"
REMOTE_B="$REMOTE-b"
REMOTE_C="$REMOTE-c"
if [ -z "$OUT" ]; then OUT="$(mktemp -d "${TMPDIR:-/tmp}/dlap-drill.XXXXXX")"; else mkdir -p "$OUT"; fi
WORK="$OUT/work"
mkdir -p "$WORK"
RESULTS="$OUT/results.env"
: > "$RESULTS"
CHECKS_FAILED=0
CHECKS_PASSED=0

# --- Evidence -----------------------------------------------------------------------------------------

record() { echo "$1=$2" >> "$RESULTS"; }
pass() { CHECKS_PASSED=$((CHECKS_PASSED + 1)); say "PASS  $*"; record "CHECK_$CHECKS_PASSED" "pass: $*"; }
fail() { CHECKS_FAILED=$((CHECKS_FAILED + 1)); say "FAIL  $*" >&2; record "CHECK_FAILED_$CHECKS_FAILED" "$*"; }
expect() { # <description> <command...>
  local d="$1"
  shift
  if "$@" > /dev/null 2>&1; then pass "$d"; else fail "$d"; fi
}

# --- Environment of the drill -------------------------------------------------------------------------

rand_hex() { openssl rand -hex "$1"; }
KEY="$(rand_hex 32)"
FP="$(DLAP_ENCRYPTION_KEY="$KEY" bash "$SCRIPT_DIR/key-escrow.sh" fingerprint)"
printf '%s\n' "$(openssl rand -base64 36)" > "$WORK/passphrase"
chmod 600 "$WORK/passphrase"
ENV="$WORK/env.drill"
cp "$PILOT_DIR/.env.pilot.example" "$ENV"
{
  echo ""
  echo "# Generated by restore-drill.sh. Random, used once, never reused."
  echo "POSTGRES_PASSWORD=$(rand_hex 12)"
  echo "KEYCLOAK_ADMIN_PASSWORD=$(rand_hex 12)"
  echo "DLAP_BACKEND_CLIENT_SECRET=$(rand_hex 12)"
  echo "DLAP_ENCRYPTION_KEY=$KEY"
  echo "DLAP_ENCRYPTION_KEY_FINGERPRINT=$FP"
  echo "GCP_PROJECT_ID=drill-project"
  echo "DLAP_BACKUP_PASSPHRASE_FILE=$(hostpath "$WORK/passphrase")"
  # Single quotes, as docs/guides/backup-and-restore.md tells an operator: Compose expands $1 and $2 otherwise.
  echo "DLAP_BACKUP_SHIP_CMD='mkdir -p \"\$(dirname \"/remote/\$2\")\" && cp \"\$1\" \"/remote/\$2\"'"
} >> "$ENV"

# The pilot TLS overlay (SEC-06) is one of the docker-compose.pilot.*.yml files: PostgreSQL waits for
# its tls-init and refuses TCP without TLS. The drill makes key material of its own, so it also proves
# that the backup agent and the restores work under the TLS-only pg_hba.conf.
bash "$PILOT_DIR/tls/gen-certs.sh" --out "$WORK/tls" --force > "$WORK/gen-certs.log" 2>&1 \
  || { cat "$WORK/gen-certs.log" >&2; refuse "gen-certs.sh failed (see $WORK/gen-certs.log)"; }
echo "PILOT_TLS_DIR=$(hostpath "$WORK/tls")" >> "$ENV"

declare -A ARGS_CACHE=()
# dc <project> <compose arguments...>
dc() {
  local proj="$1"
  shift
  if [ -z "${ARGS_CACHE[$proj]:-}" ]; then
    ARGS_CACHE[$proj]="$(COMPOSE_PROJECT_NAME="$proj" bash "$PILOT_DIR/up.sh" --env-file "$ENV" --print-args | tr '\n' '\001')"
  fi
  local -a base=()
  local a
  # MSYS_NO_PATHCONV stops Git Bash from rewriting container paths such as /opt/dr, so the host paths
  # that up.sh printed are turned into the form docker understands here.
  while IFS= read -r -d $'\001' a; do
    if [[ "$a" == /* ]] && [ -e "$a" ]; then a="$(hostpath "$a")"; fi
    base+=("$a")
  done <<< "${ARGS_CACHE[$proj]}"
  # Stack A ships to the store that survives; B and C ship to stores of their own and read the survivor.
  local sink="$REMOTE"
  case "$proj" in "$PB") sink="$REMOTE_B" ;; "$PC") sink="$REMOTE_C" ;; esac
  DR_DRILL_PREFIX="$proj" DR_DRILL_REMOTE="$sink" DR_DRILL_SOURCE="$REMOTE" docker compose "${base[@]}" -f "$(hostpath "$SCRIPT_DIR/docker-compose.drill.yml")" "$@"
}
pgc() { printf '%s-postgresql' "$1"; }
bak() { printf '%s-backup' "$1"; }
sql_in() { local proj="$1"; shift; docker exec -i "$(pgc "$proj")" psql -X -q -v ON_ERROR_STOP=1 -U dlap "$@"; }
sql() { local proj="$1" db="$2"; shift 2; sql_in "$proj" -d "$db" -At -c "$1"; }
agent() { local proj="$1"; shift; docker exec "$(bak "$proj")" bash /opt/dr/agent.sh "$@"; }

wait_tcp() { # <project> [seconds]
  local c i=0 limit="${2:-240}"
  c="$(pgc "$1")"
  until docker exec "$c" pg_isready -q -h 127.0.0.1 -U dlap -d dlap_common > /dev/null 2>&1; do
    i=$((i + 1))
    [ "$i" -le "$limit" ] || return 1
    sleep 1
  done
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
remote_sh() { docker run --rm -v "$REMOTE:/remote:ro" --entrypoint bash "$IMAGE" -c "$1"; }

cleanup() {
  local rc=$?
  if [ "$rc" -ne 0 ] || [ "$CHECKS_FAILED" -gt 0 ]; then
    for p in "$PA" "$PB" "$PC"; do
      for c in "$(pgc "$p")" "$(bak "$p")"; do
        if docker inspect "$c" > /dev/null 2>&1; then docker logs --tail 40 "$c" > "$OUT/log-$c.txt" 2>&1 || true; fi
      done
    done
  fi
  if [ "$KEEP" -eq 0 ]; then
    for p in "$PA" "$PB" "$PC"; do dc "$p" down -v --remove-orphans > /dev/null 2>&1 || true; done
    docker volume rm "$REMOTE" "$REMOTE_B" "$REMOTE_C" > /dev/null 2>&1 || true
    # The drill's own throwaway CA and keys are not evidence.
    rm -rf "$WORK/tls"
  else
    say "--keep: projects $PA $PB $PC and volume $REMOTE are left in place; remove them with docker compose down -v"
  fi
  if [ "$rc" -ne 0 ] && [ "$CHECKS_FAILED" -eq 0 ]; then say "stopped early with status $rc; evidence in $OUT" >&2; fi
}
trap cleanup EXIT

# --- Database tier ------------------------------------------------------------------------------------

# Service migration folders, the database and role each runs in, the schema Flyway manages.
SERVICES="da-admin-service:da_admin:da_admin_user:dlap_admin
da-autoarchival:da_autoarchival:da_autoarchival_user:dlap_autoarchival
da-autocompliance:da_autocompliance:da_autocompliance_user:dlap_autocompliance
da-autodelete:da_autodelete:da_autodelete_user:dlap_autodelete
da-autolabel:da_autolabel:da_autolabel_user:dlap_autolabel
da-catalog:dlap_common:da_catalog_user:dlap_catalog
da-discovery:da_discovery:da_discovery_user:dlap_discovery
da-logingest:da_logingest:da_logingest_user:dlap_logingest
da-policyengine:da_policyengine:da_policyengine_user:dlap_policyengine
da-reporting:da_reporting:da_reporting_user:dlap_reporting"

start_postgres() { # <project>
  dc "$1" up -d --wait --wait-timeout 300 postgresql > "$WORK/up-$1.log" 2>&1 || { cat "$WORK/up-$1.log" >&2; return 1; }
  wait_tcp "$1" || return 1
}

# run_upgrades <project>: what up.sh does before it starts the services (ADR-0056).
run_upgrades() {
  DLAP_COMMON_DB_URL=dlap_common DLAP_PSQL="docker exec -i $(pgc "$1") psql -U dlap" bash "$UPGRADE_RUNNER" > "$WORK/upgrades-$1.log" 2>&1
}

# seed_baseline <project>: the demo rows of dlap_common, then the Flyway migrations of every service,
# each as the role the service connects with, then a Flyway history table as Flyway writes it.
seed_baseline() {
  local p="$1" line svc db user schema f n ver desc
  sql_in "$p" -d postgres < "$INIT_SEED" > "$WORK/seed-demo.log" 2>&1 || true
  while IFS= read -r line; do
    IFS=: read -r svc db user schema <<< "$line"
    n=0
    {
      echo "SET search_path TO $schema, public;"
      echo "CREATE TABLE IF NOT EXISTS $schema.flyway_schema_history (installed_rank integer NOT NULL PRIMARY KEY, version varchar(50), description varchar(200) NOT NULL, type varchar(20) NOT NULL, script varchar(1000) NOT NULL, checksum integer, installed_by varchar(100) NOT NULL, installed_on timestamp NOT NULL DEFAULT now(), execution_time integer NOT NULL, success boolean NOT NULL);"
    } > "$WORK/hist-$svc.sql"
    for f in $(ls "$REPO_ROOT/$svc/src/main/resources/db/migration"/V*.sql | sort -V); do
      docker exec -i -e "PGOPTIONS=-c search_path=$schema,public" "$(pgc "$p")" psql -X -q -v ON_ERROR_STOP=1 -U "$user" -d "$db" < "$f" > "$WORK/migrate-$svc.log" 2>&1 \
        || { cat "$WORK/migrate-$svc.log" >&2; echo "migration $(basename "$f") of $svc failed" >&2; return 1; }
      n=$((n + 1))
      ver="$(basename "$f" | sed -E 's/^V([0-9]+)__.*/\1/')"
      desc="$(basename "$f" .sql | sed -E 's/^V[0-9]+__//; s/_/ /g')"
      echo "INSERT INTO $schema.flyway_schema_history VALUES ($n, '$ver', '$desc', 'SQL', '$(basename "$f")', 0, '$user', now(), 5, true);" >> "$WORK/hist-$svc.sql"
    done
    docker exec -i "$(pgc "$p")" psql -X -q -v ON_ERROR_STOP=1 -U "$user" -d "$db" < "$WORK/hist-$svc.sql" > /dev/null
  done <<< "$SERVICES"
  # One stored cloud credential, so the manifest has something to count.
  sql "$p" da_admin "INSERT INTO dlap_admin.dlap_cloud_connection (id, name, provider_type, enabled, created_by, created_date, encrypted_credentials) VALUES ('drill-1', 'drill connection', 'GCP', true, 'drill', now(), 'v2:ZHJpbGw=:ZHJpbGw=')" > /dev/null 2>&1 || true
}

# bulk_events <project> <count> <label>: synthetic log events of the current month.
bulk_events() {
  sql_in "$1" -d da_logingest -U da_logingest_user > /dev/null <<SQL
SET search_path TO dlap_logingest, public;
INSERT INTO log_events (event_id, event_time, ingest_time, source_provider, source_service, change_type, operation, result_status, principal_id, principal_type, target_resource_id, target_resource_kind, target_project, target_region, schema_version, raw_event, binding_id, native_event_id)
SELECT '$3-' || g, clock_timestamp(), clock_timestamp(), 'GCP', 'storage.googleapis.com', 'DATA_ACCESS', 'storage.objects.get', 'SUCCESS',
       'principal-' || (g % 50), 'USER', 'bucket-' || (g % 20) || '/object-' || g, 'GCS_OBJECT', 'drill-project', 'us-central1', '1',
       jsonb_build_object('g', g, 'label', '$3', 'pad', md5(random()::text) || md5(random()::text) || md5(random()::text) || md5(random()::text) || md5(random()::text) || md5(random()::text) || md5(random()::text) || md5(random()::text) || md5(random()::text) || md5(random()::text) || md5(random()::text) || md5(random()::text)), 'drill-binding', 'native-' || g
  FROM generate_series(1, $2) g;
SQL
}

snapshot() { # <project> <file>
  docker exec "$(bak "$1")" bash /opt/dr/rowcounts.sh --digest > "$2"
}

same_as() { # <project> <expected file> <label>
  local got="$WORK/got-$3.tsv"
  snapshot "$1" "$got"
  if diff "$2" "$got" > "$WORK/diff-$3.txt" 2>&1; then
    pass "$3: every table has the same rows and the same content digest ($(wc -l < "$got" | tr -d ' ') tables, $(awk -F'\t' '{ s += $3 } END { printf "%.0f", s }' "$got") rows)"
  else
    fail "$3: the restored data differs from the original (see $WORK/diff-$3.txt)"
    head -n 20 "$WORK/diff-$3.txt" >&2
  fi
}

# ======================================================================================================
say "evidence goes to $OUT; projects $PA, $PB, $PC; off-host volume $REMOTE; $ROWS events in the first batch"
docker image inspect "$IMAGE" > /dev/null 2>&1 || docker pull "$IMAGE" > /dev/null
docker volume create "$REMOTE" > /dev/null
docker volume create "$REMOTE_B" > /dev/null
docker volume create "$REMOTE_C" > /dev/null
# The off-host store stands in for a bucket the agent may write to: give the volume to uid 999.
for v in "$REMOTE" "$REMOTE_B" "$REMOTE_C"; do
  docker run --rm -v "$v:/remote" --entrypoint bash "$IMAGE" -c 'chown 999:999 /remote && chmod 700 /remote'
done
T_START="$(now)"

# --- 1. Stack A ---------------------------------------------------------------------------------------
t0="$(now)"
say "stack A: starting PostgreSQL from the init scripts"
start_postgres "$PA" || { fail "PostgreSQL of stack A did not become ready"; exit 1; }
run_upgrades "$PA" || { fail "the upgrade runner failed on stack A (see $WORK/upgrades-$PA.log)"; exit 1; }
seed_baseline "$PA" || { fail "seeding stack A failed"; exit 1; }
bulk_events "$PA" 500 "d0"
record SEED_SECONDS "$(( $(now) - t0 ))"
say "stack A: seeded in $(( $(now) - t0 ))s; starting the backup agent"

dc "$PA" up -d --wait --wait-timeout 300 dlap-backup > "$WORK/up-agent-$PA.log" 2>&1 || { cat "$WORK/up-agent-$PA.log" >&2; fail "the backup agent of stack A did not start"; exit 1; }
expect "the agent container is running" test "$(docker inspect -f '{{.State.Running}}' "$(bak "$PA")")" = true
expect "the single-quoted ship command in the environment file reaches the agent with \$1 and \$2 intact"   test "$(docker exec "$(bak "$PA")" printenv DLAP_BACKUP_SHIP_CMD)" = 'mkdir -p "$(dirname "/remote/$2")" && cp "$1" "/remote/$2"'
if wait_for 180 docker exec "$(bak "$PA")" bash -c "grep -q '^LAST_SUCCESS_UTC=' /backups/status/logical.env && grep -q '^LAST_SUCCESS_UTC=' /backups/status/base.env"; then
  pass "a fresh agent took the first logical and the first base backup by itself"
else
  fail "the agent did not take its first backups within 180 s"
fi
ARCHIVE_A="$(docker exec "$(bak "$PA")" bash -c "grep '^ARCHIVE_KEY=' /backups/status/wal.env | cut -d= -f2")"
expect "the WAL archive is streaming into a directory named after the cluster and timeline ($ARCHIVE_A)" test -n "$ARCHIVE_A"
expect "the server's slot retention is bounded by the agent" test "$(sql "$PA" postgres 'SHOW max_slot_wal_keep_size')" = "4GB"
expect "the agent's WAL stream runs over TLS (the TLS overlay's pg_hba.conf refuses TCP without it)" test "$(sql "$PA" postgres 'SELECT count(*) FROM pg_stat_replication r JOIN pg_stat_ssl s USING (pid) WHERE s.ssl')" -ge 1
expect "no TCP session of stack A is without TLS" test "$(sql "$PA" postgres 'SELECT count(*) FROM pg_stat_activity a JOIN pg_stat_ssl s USING (pid) WHERE a.client_addr IS NOT NULL AND NOT s.ssl')" = 0

bulk_events "$PA" "$ROWS" "d1"
t1="$(now)"
agent "$PA" once logical > "$WORK/once-logical.log" 2>&1 || { cat "$WORK/once-logical.log" >&2; fail "agent.sh once logical failed"; }
record LOGICAL_BACKUP_SECONDS "$(( $(now) - t1 ))"
snapshot "$PA" "$WORK/counts-l1.tsv"
LOGICAL_SET="$(docker exec "$(bak "$PA")" bash -c "grep '^LAST_SUCCESS_SET=' /backups/status/logical.env | cut -d= -f2")"
record LOGICAL_SET "$LOGICAL_SET"
record LOGICAL_DUMP_BYTES "$(docker exec "$(bak "$PA")" bash -c "grep '^DUMP_BYTES=' /backups/logical/$LOGICAL_SET/manifest.env | cut -d= -f2")"
record DATABASE_BYTES_AT_LOGICAL_BACKUP "$(sql "$PA" postgres 'SELECT sum(pg_database_size(datname)) FROM pg_database')"

bulk_events "$PA" "$((ROWS / 2))" "d2"
sql "$PA" postgres "SELECT pg_create_restore_point('drill_rp')" > /dev/null
snapshot "$PA" "$WORK/counts-rp.tsv"
sleep 2
bulk_events "$PA" "$((ROWS / 4))" "d3"
sql "$PA" da_admin "UPDATE dlap_admin.dlap_cloud_connection SET name = 'changed after the restore point' WHERE id = 'drill-1'" > /dev/null 2>&1 || true
T_LAST_COMMIT="$(now)"
snapshot "$PA" "$WORK/counts-final.tsv"
record BASE_BACKUP_BYTES "$(docker exec "$(bak "$PA")" bash -c "grep '^BASE_BYTES=' /backups/base/*/manifest.env | head -n 1 | cut -d= -f2")"
say "stack A: data written; waiting for the agent to copy the WAL off the host (cycle 30 s)"
if wait_for 600 docker exec "$(bak "$PA")" bash -c "[ \"\$(grep '^WAL_SHIPPED_UPTO_EPOCH=' /backups/status/ship-wal.env | cut -d= -f2)\" -gt $T_LAST_COMMIT ] && grep -q '^PENDING=0\$' /backups/status/ship-wal.env"; then
  SHIPPED_AT="$(now)"
  record OFFHOST_LAG_SECONDS "$((SHIPPED_AT - T_LAST_COMMIT))"
  pass "the off-host copy covered the last commit $((SHIPPED_AT - T_LAST_COMMIT)) s after it (WAL cycle 30 s in the drill, 300 s by default; RPO target 900 s)"
else
  fail "the WAL was not copied off the host within 600 s of the last commit"
fi
# The sets are shipped by the agent after each backup and again every cycle. A large base backup takes a while.
sets_shipped() { remote_sh "test -f /remote/logical/$LOGICAL_SET/SHA256SUMS.shipped && ls /remote/base/*/SHA256SUMS.shipped > /dev/null 2>&1"; }
wait_for 1200 sets_shipped || true
expect "the logical set is complete on the off-host volume (SHA256SUMS.shipped is there)" remote_sh "test -f /remote/logical/$LOGICAL_SET/SHA256SUMS.shipped"
expect "a base backup is complete on the off-host volume" remote_sh "ls /remote/base/*/SHA256SUMS.shipped"
expect "WAL segments are on the off-host volume" remote_sh "ls /remote/wal/$ARCHIVE_A/ | grep -q 'gz.enc'"
expect "nothing on the off-host volume is readable as plain text (no dump or WAL file without .enc)" remote_sh "! find /remote -type f \\( -name '*.dump' -o -name '*.tar.gz' -o -name 'base.tar.gz' \\) | grep -q ."
record DATABASE_BYTES_AT_DISASTER "$(sql "$PA" postgres 'SELECT sum(pg_database_size(datname)) FROM pg_database')"
record WAL_ARCHIVE_BYTES "$(docker exec "$(bak "$PA")" bash -c "du -sb /backups/wal | cut -f1")"
record REMOTE_BYTES "$(remote_sh 'du -sb /remote | cut -f1')"
record WAL_REMOTE_FILES "$(remote_sh "ls /remote/wal/$ARCHIVE_A | wc -l")"
say "stack A: status of the agent before the disaster"
agent "$PA" status | sed 's/^/    /' | tee "$OUT/agent-status-before.txt"
expect "agent.sh status --check passes on the protected stack" agent "$PA" status --check

# --- 2. Disaster --------------------------------------------------------------------------------------
say "DISASTER: removing stack A and all of its volumes"
dc "$PA" down -v --remove-orphans > "$WORK/down-$PA.log" 2>&1 || true
expect "stack A and its volumes are gone" bash -c "! docker volume ls -q | grep -q '^${PA}_'"
expect "the off-host volume survived" docker volume inspect "$REMOTE"

# --- 3. Stack B: logical restore ----------------------------------------------------------------------
t0="$(now)"
say "stack B: fresh PostgreSQL, then restore.sh from the off-host copy of set $LOGICAL_SET"
start_postgres "$PB" || { fail "PostgreSQL of stack B did not become ready"; exit 1; }
run_upgrades "$PB" || fail "the upgrade runner failed on stack B"
T_STACK_B="$(( $(now) - t0 ))"
t1="$(now)"
if dc "$PB" run --rm -T --entrypoint bash dlap-backup /opt/dr/restore.sh --from "/source/logical/$LOGICAL_SET" \
     --yes-replace-databases --work-dir /backups/tmp-restore > "$OUT/restore-logical.log" 2>&1; then
  pass "restore.sh from the encrypted off-host set exited 0"
else
  fail "restore.sh failed (see $OUT/restore-logical.log)"
  tail -n 30 "$OUT/restore-logical.log" >&2
fi
T_RESTORE_B="$(( $(now) - t1 ))"
record LOGICAL_RESTORE_SECONDS "$(sed -n 's/.*restored in \([0-9]*\)s.*/\1/p' "$OUT/restore-logical.log" | tail -n 1)"
record LOGICAL_RESTORE_TOTAL_SECONDS "$T_RESTORE_B"
record LOGICAL_STACK_START_SECONDS "$T_STACK_B"
expect "restore.sh reported RESTORE VERIFIED" grep -q 'RESTORE VERIFIED' "$OUT/restore-logical.log"
dc "$PB" up -d --wait --wait-timeout 300 dlap-backup > "$WORK/up-agent-$PB.log" 2>&1 || fail "the backup agent of stack B did not start"
same_as "$PB" "$WORK/counts-l1.tsv" "logical restore"
if wait_for 240 docker exec "$(bak "$PB")" bash -c "grep -q '^LAST_SUCCESS_UTC=' /backups/status/logical.env && grep -q '^LAST_SUCCESS_UTC=' /backups/status/base.env"; then
  pass "the backup agent resumed on the restored stack and took its first backups"
else
  fail "the agent of stack B did not take its first backups within 240 s"
fi
ARCHIVE_B="$(docker exec "$(bak "$PB")" bash -c "grep '^ARCHIVE_KEY=' /backups/status/wal.env | cut -d= -f2")"
expect "the restored stack archives into its own directory ($ARCHIVE_B, not $ARCHIVE_A)" test -n "$ARCHIVE_B" -a "$ARCHIVE_B" != "$ARCHIVE_A"
sink_has_wal() { docker run --rm -v "$1:/remote:ro" --entrypoint bash "$IMAGE" -c "ls /remote/wal/$2 2> /dev/null | grep -q 'gz.enc'"; }
sink_b_has_wal() { sink_has_wal "$REMOTE_B" "$ARCHIVE_B"; }
if wait_for 180 sink_b_has_wal; then
  pass "the restored stack ships its WAL to the off-host store again"
else
  fail "the restored stack did not ship its WAL within 180 s"
fi
dc "$PB" down -v --remove-orphans > "$WORK/down-$PB.log" 2>&1 || true

# --- 4. Stack C: point-in-time restore ----------------------------------------------------------------
pitr() { # <target> <label> <expected counts file>
  local target="$1" label="$2" expected="$3" t0 tp tr
  t0="$(now)"
  dc "$PC" up --no-start postgresql > "$WORK/create-$PC.log" 2>&1 || { cat "$WORK/create-$PC.log" >&2; fail "cannot create stack C"; return 1; }
  tp="$(now)"
  if docker run --rm -v "${PC}_postgresql-data:/pgdata" -v "$REMOTE:/remote:ro" -v "$(hostpath "$SCRIPT_DIR"):/opt/dr:ro" \
       -v "$(hostpath "$WORK/passphrase"):/run/secrets/dlap_backup_passphrase:ro" --entrypoint bash "$IMAGE" \
       /opt/dr/restore-pitr.sh --from /remote --pgdata /pgdata --target "$target" > "$OUT/restore-pitr-$label.log" 2>&1; then
    pass "restore-pitr.sh to $target exited 0"
  else
    fail "restore-pitr.sh to $target failed (see $OUT/restore-pitr-$label.log)"
    tail -n 30 "$OUT/restore-pitr-$label.log" >&2
    return 1
  fi
  tr="$(now)"
  record "PITR_${label}_PREPARE_SECONDS" "$((tr - tp))"
  record "PITR_${label}_WAL_SEGMENTS" "$(sed -n 's/.*WAL: \([0-9]*\) segment.*/\1/p' "$OUT/restore-pitr-$label.log" | tail -n 1)"
  dc "$PC" up -d --wait --wait-timeout 300 postgresql > "$WORK/up-$PC-$label.log" 2>&1 || { cat "$WORK/up-$PC-$label.log" >&2; fail "PostgreSQL did not start on the restored data directory ($label)"; docker logs --tail 30 "$(pgc "$PC")" >&2 || true; return 1; }
  wait_tcp "$PC" || { fail "PostgreSQL did not accept connections after recovery ($label)"; return 1; }
  # It accepts read-only connections while it still replays WAL; services must not start before recovery ends.
  recovered() { [ "$(sql "$PC" postgres 'SELECT pg_is_in_recovery()')" = f ]; }
  wait_for 300 recovered || { fail "recovery did not finish within 300 s ($label)"; return 1; }
  record "PITR_${label}_RECOVERY_SECONDS" "$(( $(now) - tr ))"
  record "PITR_${label}_TOTAL_SECONDS" "$(( $(now) - t0 ))"
  expect "recovery to $target finished: the server accepts writes and is on a new timeline" test "$(sql "$PC" postgres 'SELECT pg_is_in_recovery()')" = f -a "$(sql "$PC" postgres 'SELECT timeline_id FROM pg_control_checkpoint()')" = 2
  dc "$PC" up -d --wait --wait-timeout 300 dlap-backup > "$WORK/up-agent-$PC-$label.log" 2>&1 || fail "the backup agent did not start after the point-in-time restore ($label)"
  same_as "$PC" "$expected" "point-in-time restore ($label)"
  if [ "$label" = latest ]; then
    if wait_for 240 docker exec "$(bak "$PC")" bash -c "grep -q '^LAST_SUCCESS_UTC=' /backups/status/logical.env && grep -q '^LAST_SUCCESS_UTC=' /backups/status/base.env"; then
      pass "the backup agent resumed after the point-in-time restore and took its first backups"
    else
      fail "the agent did not take its first backups after the point-in-time restore"
    fi
    local arch
    arch="$(docker exec "$(bak "$PC")" bash -c "grep '^ARCHIVE_KEY=' /backups/status/wal.env | cut -d= -f2")"
    expect "the agent archives the new timeline into a new directory ($arch, not $ARCHIVE_A)" test -n "$arch" -a "$arch" != "$ARCHIVE_A"
    sink_c_has_wal() { sink_has_wal "$REMOTE_C" "$arch"; }
    if wait_for 180 sink_c_has_wal; then
      pass "after the point-in-time restore the agent ships the new timeline off the host"
    else
      fail "the agent did not ship the new timeline within 180 s"
    fi
    expect "agent.sh status --check passes after the point-in-time restore" wait_for 120 docker exec "$(bak "$PC")" bash /opt/dr/agent.sh status --check --quiet
  fi
  dc "$PC" down -v --remove-orphans > "$WORK/down-$PC-$label.log" 2>&1 || true
}
if [ "$SKIP_PITR" -eq 0 ]; then
  say "stack C: point-in-time restore to the named restore point, then to the latest moment"
  pitr "name:drill_rp" "restorepoint" "$WORK/counts-rp.tsv" || true
  pitr "latest" "latest" "$WORK/counts-final.tsv" || true
fi

# --- Report -------------------------------------------------------------------------------------------
record TOTAL_SECONDS "$(( $(now) - T_START ))"
record CHECKS_PASSED "$CHECKS_PASSED"
record CHECKS_FAILED "$CHECKS_FAILED"
echo
echo "restore drill: $CHECKS_PASSED checks passed, $CHECKS_FAILED failed, $(( $(now) - T_START )) s in all"
echo "timings and sizes (also in $RESULTS):"
grep -E '^(SEED_SECONDS|LOGICAL_|PITR_|OFFHOST_|DATABASE_BYTES|WAL_ARCHIVE_BYTES|BASE_BACKUP_BYTES|REMOTE_BYTES|WAL_REMOTE_FILES|TOTAL_SECONDS)' "$RESULTS" | sed 's/^/    /'
[ "$CHECKS_FAILED" -eq 0 ]
