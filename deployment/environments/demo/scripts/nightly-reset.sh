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
# Nightly reset of the demo to its golden state (DEMO-01).
#
# Whatever visitors did in the day (policies, tasks, labels, reports) is undone by restoring the dump
# that seed.sh took. Replay is not repeated: the reset restores.
#
#   1. verify the golden dump (SHA256SUMS)
#   2. switch the maintenance page on (the file <state>/flags/maintenance, which Caddy watches)
#   3. stop the JVM services
#   4. restore every database (pg_restore -C --clean --if-exists)
#   5. wipe Kafka: remove the broker and its volume, start a new one
#   6. start the services in two waves and wait until they are healthy
#   7. smoke test: the gateway answers, and the catalog holds the golden asset count
#   8. switch the maintenance page off and write <state>/last-reset.json
#
# Keycloak, Postgres roles, Caddy and the gateway are not touched: accounts that the founder created
# for prospects survive the reset. A failure leaves the maintenance page ON and the status "failed",
# so visitors see an honest page instead of a broken demo. `--check` reports it for monitoring.
#
# Usage:  nightly-reset.sh [options]
#   --env-file FILE       environment file (default .env.demo)
#   --golden DIR          golden dump folder (default: <state>/golden)
#   --dry-run             print the steps, change nothing
#   --check               do not reset: exit 0 when the last reset was ok and recent, 1 otherwise
#   --max-age-hours N     with --check, the oldest acceptable reset (default 26)
#   -h, --help            this text
#
# Cron, on the demo host, 03:00 local time (README.md has the full lines):
#   0 3 * * *  <repo>/deployment/environments/demo/scripts/nightly-reset.sh >> <state>/reset.log 2>&1
# Takes 6 to 10 minutes on 2 vCPU; measure.sh --reset records the real time.
#
# Exit: 0 reset done (or --check ok); 1 failed.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

GOLDEN=""
DRY=0
CHECK=0
MAX_AGE_H=26

usage() { awk '/^# Nightly reset of the demo/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) [ $# -ge 2 ] || demo_die "--env-file needs a file name"; DEMO_ENV_FILE="$2"; shift 2 ;;
    --golden) [ $# -ge 2 ] || demo_die "--golden needs a folder"; GOLDEN="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    --check) CHECK=1; shift ;;
    --max-age-hours) [ $# -ge 2 ] || demo_die "--max-age-hours needs a number"; MAX_AGE_H="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) demo_die "unknown argument: $1" ;;
  esac
done

STATE="$(demo_state_dir)"
STATUS_FILE="$STATE/last-reset.json"

# Read a field of the flat JSON this script writes.
json_field() { # json_field <file> <name>
  sed -n -E "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"?([^\",}]*)\"?.*/\1/p" "$1" | head -n 1
}

epoch_of() { # epoch_of <ISO 8601 UTC>
  date -u -d "$1" +%s 2>/dev/null || date -u -j -f '%Y-%m-%dT%H:%M:%SZ' "$1" +%s 2>/dev/null || echo 0
}

# --- --check: for monitoring ----------------------------------------------------------------------

if [ "$CHECK" = "1" ]; then
  [ -f "$STATUS_FILE" ] || { echo "nightly-reset: no reset has run yet ($STATUS_FILE is missing)" >&2; exit 1; }
  st="$(json_field "$STATUS_FILE" status)"
  fin="$(json_field "$STATUS_FILE" finished_utc)"
  if [ "$st" != "ok" ]; then
    echo "nightly-reset: last reset status is '$st' (finished '${fin:-never}')" >&2
    exit 1
  fi
  age_h=$(( ( $(date -u +%s) - $(epoch_of "$fin") ) / 3600 ))
  if (( age_h > MAX_AGE_H )); then
    echo "nightly-reset: last good reset finished $fin, ${age_h}h ago (limit ${MAX_AGE_H}h)" >&2
    exit 1
  fi
  echo "nightly-reset: ok, last reset finished $fin (${age_h}h ago)"
  exit 0
fi

# --- The reset ------------------------------------------------------------------------------------

demo_init "$DEMO_ENV_FILE"
[ -n "$GOLDEN" ] || GOLDEN="$STATE/golden"
FLAG="$STATE/flags/maintenance"
mkdir -p "$STATE/flags"

# run <description> <command...>: print the step, run it unless this is a dry run.
run() {
  local what="$1"; shift
  demo_say "$what"
  if [ "$DRY" = "1" ]; then return 0; fi
  "$@"
}

started_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
t_start=$SECONDS
phase="start"

write_status() { # write_status <status> [detail]
  local st="$1" detail="${2:-}" fin
  fin="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  [ "$DRY" = "1" ] && return 0
  printf '{"status":"%s","phase":"%s","started_utc":"%s","finished_utc":"%s","duration_s":%s,"assets_expected":"%s","assets_found":"%s","detail":"%s"}\n' \
    "$st" "$phase" "$started_utc" "$fin" "$((SECONDS - t_start))" "${ASSETS_EXPECTED:-}" "${ASSETS_FOUND:-}" "$detail" > "$STATUS_FILE"
}

DONE=0
# Runs on every exit, including the exits of demo_die and of a failed command under set -e.
on_exit() {
  local rc=$?
  if [ "$DONE" != "1" ] && [ "$DRY" != "1" ] && [ "$STARTED" = "1" ]; then
    demo_warn "reset failed in phase '$phase' (exit $rc); the maintenance page stays ON"
    write_status failed "failed in phase $phase"
    exit 1
  fi
}
STARTED=0

ASSETS_EXPECTED=""
ASSETS_FOUND=""

[ -f "$GOLDEN/manifest.env" ] || demo_die "no golden dump in $GOLDEN (run seed.sh first)"
if [ "$DRY" != "1" ]; then demo_lock reset; fi
trap on_exit EXIT
STARTED=1

# Read the manifest in a subshell: it is a file of KEY=value lines that this script wrote itself.
DATABASES="$(grep -E '^DATABASES=' "$GOLDEN/manifest.env" | cut -d= -f2- | tr -d '"\r')"
ASSETS_EXPECTED="$(grep -E '^ASSETS=' "$GOLDEN/manifest.env" | cut -d= -f2- | tr -d '\r')"
[ -n "$DATABASES" ] || demo_die "the manifest in $GOLDEN names no databases"

phase="verify"
run "verifying the golden dump" bash -c 'cd "$1" && if command -v sha256sum >/dev/null 2>&1; then sha256sum -c --quiet SHA256SUMS; else shasum -a 256 -c --quiet SHA256SUMS; fi' _ "$GOLDEN"

phase="maintenance-on"
run "maintenance page ON" bash -c 'date -u +%Y-%m-%dT%H:%M:%SZ > "$1"' _ "$FLAG"
write_status running "reset in progress"

if [ "$DRY" != "1" ]; then
  JVMS="$(demo_jvm_services | tr '\n' ' ')"
else
  JVMS="<jvm services>"
fi

phase="stop-services"
run "stopping the JVM services: $JVMS" demo_dc stop -t 30 $JVMS

phase="postgres"
run "starting postgresql if it is not running" demo_dc up -d --wait --wait-timeout 300 postgresql
run "closing the remaining connections to the demo databases" demo_dc exec -T postgresql psql -U dlap -d postgres -qAt \
  -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname IN ($(for d in $DATABASES; do printf "'%s'," "$d"; done | sed 's/,$//')) AND pid <> pg_backend_pid()"
for db in $DATABASES; do
  phase="restore-$db"
  demo_say "restoring $db"
  if [ "$DRY" != "1" ]; then
    # -C recreates the database from the archive; --clean --if-exists drops the current one first.
    demo_dc exec -T postgresql pg_restore -U dlap -d postgres -C --clean --if-exists --exit-on-error < "$GOLDEN/$db.dump"
  fi
done

phase="kafka-wipe"
demo_say "wiping Kafka (broker and volume)"
if [ "$DRY" != "1" ]; then
  demo_dc stop kafka
  demo_dc rm -f -v kafka
  for vol in $(docker volume ls -q --filter "label=com.docker.compose.project=$(demo_project)" --filter "label=com.docker.compose.volume=kafka-kraft" | tr -d '\r'); do
    docker volume rm "$vol" > /dev/null
  done
  demo_dc up -d --wait --wait-timeout 300 kafka
fi

phase="start-services"
run "starting the JVM services in two waves (depends_on in the overlay)" demo_dc up -d --wait --wait-timeout 1200 $JVMS

phase="smoke"
if [ "$DRY" != "1" ]; then
  gw="http://127.0.0.1:$(demo_cfg DLAP_PORT_GATEWAY 8081)"
  demo_wait_http "$gw/gateway-health" 120 || demo_die "the gateway does not answer at $gw"
  ASSETS_FOUND="$(demo_pq dlap_common 'SELECT count(*) FROM dlap_common.dlap_assets')"
  demo_say "catalog assets: found ${ASSETS_FOUND:-?}, golden ${ASSETS_EXPECTED:-?}"
  [ "${ASSETS_FOUND:-x}" = "${ASSETS_EXPECTED:-y}" ] || demo_die "the catalog holds ${ASSETS_FOUND:-?} assets, the golden dump ${ASSETS_EXPECTED:-?}"
fi

phase="maintenance-off"
run "maintenance page OFF" rm -f "$FLAG"
phase="done"
DONE=1
write_status ok "reset to the golden dump of $(grep -E '^CREATED_UTC=' "$GOLDEN/manifest.env" | cut -d= -f2- | tr -d '\r')"
demo_say "reset done in $((SECONDS - t_start))s"
