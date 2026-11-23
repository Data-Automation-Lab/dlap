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
# Tests for the database upgrade step of up.sh (FU-2A, Q-142, ADR-0056). They copy up.sh into a
# scratch tree, put a stand-in `docker` first on PATH and never start a compose project.
#
#   Part 1 (always): the stand-in answers `docker compose` and `docker exec` and records what up.sh
#          does. Checks the order (PostgreSQL, readiness probe, runner, services), the runner's
#          connection, the exit status for a runner failure and a refusal, a database that is slow
#          or never ready, and that ps, logs, down, --check and --print-args never upgrade.
#   Part 2 (needs Docker and the postgres:15.1 image): a real PostgreSQL container built from the
#          repository's init scripts, a real run-upgrades.sh with the real deltas, reached through a
#          real `docker exec`. Only the `docker compose` calls are still answered by the stand-in, so
#          no compose project is created. Checks that the readiness probe waits out the init scripts,
#          that the first run applies and records every delta, that a second run applies none, and
#          that an edited delta stops the start with exit 2.
#
# Usage:  up-test.sh [--quick]
#   --quick   Part 1 only (bash is the only requirement).
# Exit:   0 every case passed (Part 2 may print SKIP); 1 a case failed.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
UP_SH="$SCRIPT_DIR/up.sh"
UPGRADES_SRC="$REPO_ROOT/deployment/platform/database/upgrades"
DB_PASSWORDS_SRC="$REPO_ROOT/deployment/platform/database/gen-db-passwords.sh"
INIT_SRC="$REPO_ROOT/deployment/platform/database/init-scripts"
ENV_EXAMPLE="$SCRIPT_DIR/.env.pilot.example"
QUICK=0
[ "${1:-}" = "--quick" ] && QUICK=1

TMP="$(mktemp -d)"
REAL_DOCKER="$(command -v docker || true)"
CONTAINER=""
trap 'cleanup' EXIT

cleanup() {
  if [ -n "$CONTAINER" ] && [ -n "$REAL_DOCKER" ]; then
    MSYS_NO_PATHCONV=1 "$REAL_DOCKER" rm -f "$CONTAINER" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP"
}

passed=0
failed=0
ok()  { passed=$((passed + 1)); echo "  ok    $*"; }
bad() { failed=$((failed + 1)); echo "  FAIL  $*" >&2; }

# check <description> <condition...>: ok when the command succeeds.
check() {
  local desc="$1"
  shift
  if "$@"; then ok "$desc"; else bad "$desc"; fi
}

# --- Scratch tree --------------------------------------------------------------------------------

# make_tree <dir> <real|fake>: the files up.sh looks at, laid out as in the repository. With "fake"
# the runner is a stand-in that records its connection settings and exits with FAKE_RUNNER_RC; with
# "real" it is the shipped runner next to copies of the shipped deltas.
make_tree() {
  local tree="$1" kind="$2" svc
  local pilot="$tree/deployment/environments/pilot" upg="$tree/deployment/platform/database/upgrades"
  mkdir -p "$pilot" "$tree/deployment/environments/dev" "$tree/deployment/platform/keycloak/keycloak-import" \
    "$upg" "$tree/new-ui/src/main/docker"
  cp "$UP_SH" "$pilot/up.sh"
  : >"$tree/deployment/environments/dev/docker-compose.yml"
  : >"$tree/deployment/environments/dev/docker-compose.microservices.yml"
  : >"$pilot/docker-compose.pilot.base.yml"
  printf '{}\n' >"$tree/deployment/platform/keycloak/keycloak-import/dlap-realm.json"
  : >"$tree/new-ui/src/main/docker/Dockerfile"
  for svc in da-discovery da-catalog da-policyengine da-reporting da-autolabel da-autocompliance da-admin-service da-logingest; do
    mkdir -p "$tree/$svc/build/libs"
    : >"$tree/$svc/build/libs/$svc.jar"
  done
  # SEC-12: the tool that names the password variables, and the role synchronisation up.sh runs after the runner.
  cp "$DB_PASSWORDS_SRC" "$tree/deployment/platform/database/gen-db-passwords.sh"
  if [ "$kind" = real ]; then
    cp "$UPGRADES_SRC"/run-upgrades.sh "$UPGRADES_SRC"/sync-database-roles.sh "$UPGRADES_SRC"/*.sql "$upg/"
  else
    printf '#!/usr/bin/env bash\necho "stand-in for sync-database-roles.sh"\n' >"$upg/sync-database-roles.sh"
    cat >"$upg/run-upgrades.sh" <<'RUNNER'
#!/usr/bin/env bash
echo "event runner psql=[$DLAP_PSQL] url=[$DLAP_COMMON_DB_URL]" >>"$FAKE_EVENTS"
exit "${FAKE_RUNNER_RC:-0}"
RUNNER
  fi
  # The example file with every placeholder replaced by an obviously artificial value.
  local a24 a30 a30x z64
  a24="$(printf 'a%.0s' $(seq 1 24))"
  a30="$(printf 'b%.0s' $(seq 1 30))"
  a30x="$(printf 'c%.0s' $(seq 1 30))"
  z64="$(printf '0%.0s' $(seq 1 64))"
  sed -e "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$a24/" \
      -e "s/^KEYCLOAK_ADMIN_PASSWORD=.*/KEYCLOAK_ADMIN_PASSWORD=$a24/" \
      -e "s/^DLAP_BACKEND_CLIENT_SECRET=.*/DLAP_BACKEND_CLIENT_SECRET=$a30/" \
      -e "s/^DLAP_MCP_GATEWAY_CLIENT_SECRET=.*/DLAP_MCP_GATEWAY_CLIENT_SECRET=$a30x/" \
      -e "s/^DLAP_ENCRYPTION_KEY=.*/DLAP_ENCRYPTION_KEY=$z64/" \
      -e "s/^GCP_PROJECT_ID=.*/GCP_PROJECT_ID=example-project/" \
      -e "s/^KEYCLOAK_DB_PASSWORD=.*/KEYCLOAK_DB_PASSWORD=$a30x/" \
      "$ENV_EXAMPLE" >"$pilot/.env.pilot"
  # The thirteen database role passwords (SEC-12), written by the tool a pilot operator uses.
  bash "$tree/deployment/platform/database/gen-db-passwords.sh" "$pilot/.env.pilot" >/dev/null
  # The scratch tree has the base overlay only, so up.sh asks for the quickstart realm to be accepted.
  # The example file no longer carries the variable (Q-510).
  printf '%s\n' "PILOT_ALLOW_DEV_REALM=1" >>"$pilot/.env.pilot"
}

# --- The docker stand-in -------------------------------------------------------------------------

# Records one "event ..." line per call in $FAKE_EVENTS. Answers `docker compose`. With
# FAKE_DOCKER_MODE=real, `docker exec` goes to the real docker after it is recorded.
make_docker_double() {
  mkdir -p "$TMP/bin"
  cat >"$TMP/bin/docker" <<'DOUBLE'
#!/usr/bin/env bash
ev="${FAKE_EVENTS:?}"
case "${1:-}" in
  compose)
    shift
    sub=""
    while [ $# -gt 0 ]; do
      case "$1" in
        --project-name|--env-file|-f|-p|--profile) shift 2 ;;
        -*) shift ;;
        *) sub="$1"; shift; break ;;
      esac
    done
    case "$sub" in
      version) echo "${FAKE_COMPOSE_VERSION:-2.29.0}" ;;
      config) echo "event compose-config" >>"$ev" ;;
      up)
        if [ "$*" = "-d postgresql" ]; then
          echo "event up-postgres" >>"$ev"
          exit "${FAKE_UP_POSTGRES_RC:-0}"
        fi
        echo "event up-all $*" >>"$ev"
        exit "${FAKE_UP_ALL_RC:-0}"
        ;;
      ps)
        if [ "$*" = "-q postgresql" ]; then
          echo "event ps-postgres" >>"$ev"
          printf '%s\n' "${FAKE_CID-fakecid0123}"
        else
          echo "event compose-ps $*" >>"$ev"
        fi
        ;;
      *) echo "event compose-$sub $*" >>"$ev" ;;
    esac
    exit 0
    ;;
  exec)
    echo "event exec $*" >>"$ev"
    if [ "${FAKE_DOCKER_MODE:-fake}" = real ]; then
      MSYS_NO_PATHCONV=1 exec "$FAKE_REAL_DOCKER" "$@"
    fi
    case "$*" in
      *"bash -s"*) exit "${FAKE_SYNC_RC:-0}" ;;
      *pg_isready*)
        # FAKE_PG_FAIL_FIRST=N: the first N probes fail, then they pass.
        if [ -n "${FAKE_PG_FAIL_FIRST:-}" ]; then
          n="$(cat "$FAKE_EVENTS.probes" 2>/dev/null || echo 0)"
          echo $((n + 1)) >"$FAKE_EVENTS.probes"
          [ "$n" -lt "$FAKE_PG_FAIL_FIRST" ] && exit 1
          exit 0
        fi
        exit "${FAKE_PG_ISREADY_RC:-0}"
        ;;
    esac
    exit 0
    ;;
  *)
    echo "event docker $*" >>"$ev"
    exit 0
    ;;
esac
DOUBLE
  chmod +x "$TMP/bin/docker"
}

# run_up <tree> [up.sh arguments...]: runs the copy of up.sh in <tree> with the stand-in first on
# PATH. Sets RC, OUT (both streams) and EVENTS (the recorded events, one per line).
RC=0
OUT=""
EVENTS=""
run_up() {
  local tree="$1"
  shift
  rm -f "$TMP/events" "$TMP/events.probes"
  : >"$TMP/events"
  OUT="$(PATH="$TMP/bin:$PATH" FAKE_EVENTS="$TMP/events" FAKE_REAL_DOCKER="$REAL_DOCKER" \
    bash "$tree/deployment/environments/pilot/up.sh" "$@" 2>&1)"
  RC=$?
  EVENTS="$(cat "$TMP/events")"
}

has_event()  { grep -qE -- "$1" <<<"$EVENTS"; }
no_event()   { ! has_event "$1"; }
out_has()    { grep -qF -- "$1" <<<"$OUT"; }
# line number of the first event matching a pattern, or 0
event_line() { grep -nE -- "$1" <<<"$EVENTS" | head -n 1 | cut -d: -f1 | grep . || echo 0; }
# before <pattern A> <pattern B>: both happened, A first
before() {
  local a b
  a="$(event_line "$1")"
  b="$(event_line "$2")"
  [ "$a" -gt 0 ] && [ "$b" -gt 0 ] && [ "$a" -lt "$b" ]
}

# =================================================================================================
echo "up.sh database upgrade, stand-in docker"
make_docker_double
T1="$TMP/tree-fake"
make_tree "$T1" fake

run_up "$T1"
check "default up: exit 0" test "$RC" -eq 0
check "default up: PostgreSQL alone is started first" before '^event up-postgres$' '^event ps-postgres$'
check "default up: the container is probed after it is found" before '^event ps-postgres$' '^event exec .*pg_isready'
check "default up: the readiness probe uses TCP on 127.0.0.1" has_event 'exec fakecid0123 pg_isready -q -h 127\.0\.0\.1 -U dlap -d dlap_common'
check "default up: the runner runs after the probe" before 'pg_isready' '^event runner'
check "default up: the runner runs before any service starts" before '^event runner' '^event up-all'
check "default up: the services start with up -d --build" has_event '^event up-all -d --build$'
check "default up: the runner reaches the container through docker exec" \
  has_event '^event runner psql=\[docker exec -i fakecid0123 psql -U dlap\] url=\[dlap_common\]$'

run_up "$T1" up -d da-catalog
check "up with a service list: exit 0" test "$RC" -eq 0
check "up with a service list: still upgrades first" before '^event runner' '^event up-all'
check "up with a service list: the arguments reach compose" has_event '^event up-all -d da-catalog$'

DLAP_COMMON_DB_URL=elsewhere PGDATABASE=elsewhere run_up "$T1"
check "the caller's connection variables do not reach the runner" has_event 'url=\[dlap_common\]$'

check "default up: the database roles are synchronised in the container, with the script on stdin" \
  has_event '^event exec exec -i fakecid0123 bash -s -- --quiet$'
check "default up: the roles are synchronised after the runner" \
  before '^event runner' '^event exec exec -i fakecid0123 bash -s'
check "default up: the roles are synchronised before any service starts" before 'bash -s -- --quiet' '^event up-all'

FAKE_SYNC_RC=1 run_up "$T1"
check "a failed role synchronisation: exit 1" test "$RC" -eq 1
check "a failed role synchronisation: no service starts" no_event '^event up-all'
check "a failed role synchronisation: the message says so" out_has "role synchronisation failed"

FAKE_RUNNER_RC=2 run_up "$T1"
check "a refusal by the runner: exit 2" test "$RC" -eq 2
check "a refusal by the runner: no service starts" no_event '^event up-all'
check "a refusal by the runner: the message says so" out_has "no service was started"

FAKE_RUNNER_RC=1 run_up "$T1"
check "a failure of the runner: exit 1" test "$RC" -eq 1
check "a failure of the runner: no service starts" no_event '^event up-all'

FAKE_PG_ISREADY_RC=1 PILOT_DB_WAIT_SECONDS=1 run_up "$T1"
check "PostgreSQL never ready: exit 1" test "$RC" -eq 1
check "PostgreSQL never ready: the runner is not called" no_event '^event runner'
check "PostgreSQL never ready: no service starts" no_event '^event up-all'
check "PostgreSQL never ready: the message names the wait" out_has "did not accept connections within 1 seconds"

FAKE_PG_FAIL_FIRST=2 run_up "$T1"
check "PostgreSQL ready after two failed probes: exit 0" test "$RC" -eq 0
check "PostgreSQL ready after two failed probes: three probes were made" \
  test "$(grep -c 'pg_isready' <<<"$EVENTS")" -eq 3
check "PostgreSQL ready after two failed probes: the runner follows" before 'pg_isready' '^event runner'

FAKE_UP_POSTGRES_RC=5 run_up "$T1"
check "compose cannot start PostgreSQL: its exit status is returned" test "$RC" -eq 5
check "compose cannot start PostgreSQL: the runner is not called" no_event '^event runner'
check "compose cannot start PostgreSQL: no service starts" no_event '^event up-all'

FAKE_CID= run_up "$T1"
check "no PostgreSQL container: exit 1" test "$RC" -eq 1
check "no PostgreSQL container: the message says so" out_has "container is not running"
check "no PostgreSQL container: the runner is not called" no_event '^event runner'

for cmd in "ps" "logs -f da-catalog" "down" "exec da-catalog true"; do
  # shellcheck disable=SC2086
  run_up "$T1" $cmd
  check "'$cmd' does not touch the database" no_event '^event (up-postgres|runner|exec .*pg_isready)'
  check "'$cmd' reaches compose" has_event '^event compose-'
done

run_up "$T1" --check
check "--check: exit 0" test "$RC" -eq 0
check "--check: validates the compose files" has_event '^event compose-config$'
check "--check: starts nothing and upgrades nothing" no_event '^event (up-postgres|up-all|runner)'

run_up "$T1" --print-args
check "--print-args: exit 0 and no docker call" test "$RC" -eq 0 -a -z "$EVENTS"

PILOT_DB_WAIT_SECONDS=abc run_up "$T1" --check
check "a non-numeric PILOT_DB_WAIT_SECONDS is a check failure (exit 2)" test "$RC" -eq 2
check "a non-numeric PILOT_DB_WAIT_SECONDS names the variable" out_has "PILOT_DB_WAIT_SECONDS must be a whole number"

# Q-458: the dlap-mcp-gateway client secret is vetted like the backend one. A tree whose realm file carries
# the published defaults, as the shipped dlap-realm.json does.
make_gateway_tree() { # make_gateway_tree <dir> <KEY=value or __unset__>: set or drop the gateway line
  local tree="$1" kv="$2" env="$1/deployment/environments/pilot/.env.pilot"
  make_tree "$tree" fake
  printf '%s\n' '{"secret": "${DLAP_BACKEND_CLIENT_SECRET:change-me-dev-only}", "gw": "${DLAP_MCP_GATEWAY_CLIENT_SECRET:change-me-dev-only}"}' \
    >"$tree/deployment/platform/keycloak/keycloak-import/dlap-realm.json"
  grep -v '^DLAP_MCP_GATEWAY_CLIENT_SECRET=' "$env" >"$env.tmp" && mv "$env.tmp" "$env"
  [ "$kv" = __unset__ ] || printf '%s\n' "$kv" >>"$env"
}

T3="$TMP/tree-gateway"
make_gateway_tree "$T3" "DLAP_MCP_GATEWAY_CLIENT_SECRET=change-me-dev-only"
run_up "$T3" --check
check "the published MCP gateway client secret is a check failure (exit 2)" test "$RC" -eq 2
check "the published MCP gateway client secret is named" out_has "DLAP_MCP_GATEWAY_CLIENT_SECRET is the quickstart realm's published default"

make_gateway_tree "$T3" "DLAP_MCP_GATEWAY_CLIENT_SECRET=CHANGE_ME_x"
run_up "$T3" --check
check "a CHANGE_ME MCP gateway client secret is a check failure (exit 2)" test "$RC" -eq 2
check "a CHANGE_ME MCP gateway client secret is named" out_has "DLAP_MCP_GATEWAY_CLIENT_SECRET still has the placeholder"

make_gateway_tree "$T3" __unset__
run_up "$T3" --check
check "a missing MCP gateway client secret is a check failure (exit 2)" test "$RC" -eq 2
check "a missing MCP gateway client secret is named" out_has "DLAP_MCP_GATEWAY_CLIENT_SECRET is not set"

make_gateway_tree "$T3" "DLAP_MCP_GATEWAY_CLIENT_SECRET=$(printf 'd%.0s' $(seq 1 30))"
run_up "$T3" --check
check "a real MCP gateway client secret passes the check (exit 0)" test "$RC" -eq 0

# SEC-12 (Q-176, Q-449, Q-455): one real password per database role; a placeholder, a published default and a
# repeated value are refused, and so is a missing tool or role synchronisation script.
db_env_tree() { # db_env_tree <dir> [KEY=value ...]: the good tree with the given settings replaced (value __unset__ drops one)
  local tree="$1" kv env="$1/deployment/environments/pilot/.env.pilot"
  shift
  make_tree "$tree" fake
  for kv in "$@"; do
    grep -v "^${kv%%=*}=" "$env" >"$env.tmp" && mv "$env.tmp" "$env"
    [ "${kv#*=}" = __unset__ ] || printf '%s\n' "$kv" >>"$env"
  done
}
db_env_get() { grep "^$2=" "$1/deployment/environments/pilot/.env.pilot" | tail -n 1 | cut -d= -f2; }

T8="$TMP/tree-dbpw"
db_env_tree "$T8" "DA_CATALOG_DB_PASSWORD=__unset__"
run_up "$T8" --check
check "a missing database role password is a check failure (exit 2)" test "$RC" -eq 2
check "a missing database role password is named" out_has "DA_CATALOG_DB_PASSWORD is not set"

db_env_tree "$T8" "DA_ADMIN_DB_PASSWORD=CHANGE_ME_da_admin_db_password"
run_up "$T8" --check
check "a placeholder database role password is a check failure (exit 2)" test "$RC" -eq 2
check "a placeholder database role password is named" out_has "DA_ADMIN_DB_PASSWORD still has the placeholder"

db_env_tree "$T8" "DA_CATALOG_DB_PASSWORD=da_catalog_pass"
run_up "$T8" --check
check "the published default of a service role is a check failure (exit 2)" test "$RC" -eq 2
check "the published default of a service role is named" out_has "DA_CATALOG_DB_PASSWORD is a published quickstart default"

db_env_tree "$T8" "DLAP_MONITOR_DB_PASSWORD=dlap_monitor_pass"
run_up "$T8" --check
check "the published default of the monitor role is a check failure (exit 2)" test "$RC" -eq 2
check "the published default of the monitor role is named" out_has "DLAP_MONITOR_DB_PASSWORD is a published quickstart default"

db_env_tree "$T8" "DLAP_BACKUP_DB_PASSWORD=dlap_backup_pass"
run_up "$T8" --check
check "the published default of the backup role is a check failure (exit 2)" test "$RC" -eq 2
check "the published default of the backup role is named" out_has "DLAP_BACKUP_DB_PASSWORD is a published quickstart default"

db_env_tree "$T8" "DA_REPORTING_DB_PASSWORD=dlap"
run_up "$T8" --check
check "the superuser's published default as a role password is refused" out_has "DA_REPORTING_DB_PASSWORD is a published quickstart default"

db_env_tree "$T8" "DA_REPORTING_DB_PASSWORD=shared-0123456789abcdef" "DA_AUTOLABEL_DB_PASSWORD=shared-0123456789abcdef"
run_up "$T8" --check
check "a database role password used twice is a check failure (exit 2)" test "$RC" -eq 2
check "a database role password used twice is named" out_has "has the same value as another database password"

db_env_tree "$T8"
db_env_tree "$T8" "DA_REPORTING_DB_PASSWORD=$(db_env_get "$T8" POSTGRES_PASSWORD)"
run_up "$T8" --check
check "a role password equal to the superuser's is a check failure (exit 2)" test "$RC" -eq 2
check "a role password equal to the superuser's is named" out_has "DA_REPORTING_DB_PASSWORD has the same value as another database password"

db_env_tree "$T8"
run_up "$T8" --check
check "a tree with thirteen real, different role passwords passes (exit 0)" test "$RC" -eq 0

rm -f "$T8/deployment/platform/database/upgrades/sync-database-roles.sh"
run_up "$T8" --check
check "a missing role synchronisation script is a check failure (exit 2)" test "$RC" -eq 2
check "a missing role synchronisation script is named" out_has "the database role synchronisation is missing"

T2="$TMP/tree-no-runner"
make_tree "$T2" fake
rm -f "$T2/deployment/platform/database/upgrades/run-upgrades.sh"
run_up "$T2" --check
check "a missing runner is a check failure (exit 2)" test "$RC" -eq 2
check "a missing runner is named" out_has "the database upgrade runner is missing"
run_up "$T2"
check "a missing runner starts nothing" no_event '^event (up-postgres|up-all)'

# =================================================================================================
echo "up.sh checks that depend on an overlay (Q-487, Q-510, Q-517, Q-541)"

# overlay_tree <dir> <overlay...>: the scratch tree plus empty stand-ins for the named overlay files
# (keycloak, tls, identity-map). up.sh looks at the file names only. The environment is the example
# file with artificial secrets, as in make_tree; each case below edits it with env_set / env_unset.
overlay_tree() {
  local tree="$1" name
  shift
  make_tree "$tree" fake
  for name in "$@"; do
    : >"$tree/deployment/environments/pilot/docker-compose.pilot.$name.yml"
  done
}
env_file_of() { printf '%s' "$1/deployment/environments/pilot/.env.pilot"; }
env_unset() { local f; f="$(env_file_of "$1")"; grep -v "^$2=" "$f" >"$f.tmp" && mv "$f.tmp" "$f"; }
env_set() { env_unset "$1" "${2%%=*}"; printf '%s\n' "$2" >>"$(env_file_of "$1")"; }
# generated <dir> [relative folder]: key material as gen-certs.sh lays it out, with empty-but-nonzero files.
generated() {
  local base="${2:-deployment/environments/pilot/tls/generated}" f
  mkdir -p "$1/$base/certs" "$1/$base/scram"
  for f in ca.crt jvm-truststore.p12 gateway.crt keycloak.crt postgresql.crt; do printf 'x' >"$1/$base/certs/$f"; done
}

# Without the keycloak overlay the quickstart realm needs an explicit opt-in.
T4="$TMP/tree-no-kc"
overlay_tree "$T4"
env_unset "$T4" PILOT_ALLOW_DEV_REALM
run_up "$T4" --check
check "no keycloak overlay and no opt-in: exit 2" test "$RC" -eq 2
check "no keycloak overlay and no opt-in: PILOT_ALLOW_DEV_REALM is named" out_has "PILOT_ALLOW_DEV_REALM=1"
env_set "$T4" PILOT_ALLOW_DEV_REALM=1
run_up "$T4" --check
check "no keycloak overlay with the opt-in: exit 0" test "$RC" -eq 0

# With the overlay the opt-in is gone and KEYCLOAK_DB_PASSWORD is its own required secret (Q-541).
T5="$TMP/tree-kc"
overlay_tree "$T5" keycloak
env_unset "$T5" PILOT_ALLOW_DEV_REALM
run_up "$T5" --check
check "keycloak overlay, no opt-in needed: exit 0" test "$RC" -eq 0
env_set "$T5" KEYCLOAK_DB_PASSWORD=CHANGE_ME_keycloak_db_password
run_up "$T5" --check
check "a placeholder KEYCLOAK_DB_PASSWORD: exit 2" test "$RC" -eq 2
check "a placeholder KEYCLOAK_DB_PASSWORD is named" out_has "KEYCLOAK_DB_PASSWORD still has the placeholder"
env_unset "$T5" KEYCLOAK_DB_PASSWORD
run_up "$T5" --check
check "a missing KEYCLOAK_DB_PASSWORD: exit 2" test "$RC" -eq 2
check "a missing KEYCLOAK_DB_PASSWORD is named" out_has "KEYCLOAK_DB_PASSWORD is not set"
env_set "$T5" KEYCLOAK_DB_PASSWORD=keycloak
run_up "$T5" --check
check "the init script's fallback password is refused" out_has "KEYCLOAK_DB_PASSWORD is a published quickstart default"

# The TLS overlay needs its key material, an https origin and one issuer.
T6="$TMP/tree-tls"
overlay_tree "$T6" keycloak tls
run_up "$T6" --check
check "the example's addresses without key material: exit 2" test "$RC" -eq 2
check "missing key material names gen-certs.sh" out_has "gen-certs.sh --public-host"
check "missing key material names the missing file" out_has "certs/ca.crt is missing"
generated "$T6"
run_up "$T6" --check
check "key material in place and the example's addresses: exit 0" test "$RC" -eq 0
rm "$T6/deployment/environments/pilot/tls/generated/certs/keycloak.crt"
run_up "$T6" --check
check "one missing certificate (keycloak.crt) is refused" out_has "certs/keycloak.crt is missing"
generated "$T6"
rmdir "$T6/deployment/environments/pilot/tls/generated/scram"
run_up "$T6" --check
check "a missing scram folder is refused" out_has "no SCRAM passwords"
generated "$T6"

env_set "$T6" PILOT_PUBLIC_ORIGIN=http://localhost:8443
run_up "$T6" --check
check "an http PILOT_PUBLIC_ORIGIN is refused" out_has "PILOT_PUBLIC_ORIGIN must start with https://"
env_set "$T6" PILOT_PUBLIC_ORIGIN=https://localhost:8443
env_set "$T6" DLAP_PILOT_ID_URL=http://localhost:8180
run_up "$T6" --check
check "a DLAP_PILOT_ID_URL other than the origin is refused" out_has "DLAP_PILOT_ID_URL must equal PILOT_PUBLIC_ORIGIN"
check "a DLAP_PILOT_ID_URL mismatch does not blame the app URL" bash -c '! grep -qF "DLAP_PILOT_APP_URL must equal" <<<"$1"' _ "$OUT"
env_set "$T6" DLAP_PILOT_ID_URL=https://localhost:8443/
env_unset "$T6" DLAP_PILOT_APP_URL
run_up "$T6" --check
check "an unset DLAP_PILOT_APP_URL is refused" out_has "DLAP_PILOT_APP_URL must equal PILOT_PUBLIC_ORIGIN"
check "a trailing slash on DLAP_PILOT_ID_URL is accepted" bash -c '! grep -qF "DLAP_PILOT_ID_URL must equal" <<<"$1"' _ "$OUT"
env_set "$T6" DLAP_PILOT_APP_URL=https://localhost:8443
run_up "$T6" --check
check "matching addresses pass again" test "$RC" -eq 0

# PILOT_TLS_DIR: an absolute path is used as it is, a relative one is relative to deployment/environments/dev.
env_set "$T6" "PILOT_TLS_DIR=$T6/elsewhere"
run_up "$T6" --check
check "PILOT_TLS_DIR points at a folder without key material: exit 2" test "$RC" -eq 2
check "the message names the folder PILOT_TLS_DIR points at" out_has "$T6/elsewhere/certs/ca.crt is missing"
generated "$T6" "elsewhere"
run_up "$T6" --check
check "PILOT_TLS_DIR with key material (absolute): exit 0" test "$RC" -eq 0
env_set "$T6" "PILOT_TLS_DIR=../pilot/relative-tls"
run_up "$T6" --check
check "a relative PILOT_TLS_DIR is resolved against the dev folder" out_has "environments/dev/../pilot/relative-tls/certs/ca.crt is missing"
generated "$T6" "deployment/environments/pilot/relative-tls"
run_up "$T6" --check
check "a relative PILOT_TLS_DIR with key material: exit 0" test "$RC" -eq 0
env_unset "$T6" PILOT_TLS_DIR

# The shell environment wins over the file, as in docker compose.
PILOT_PUBLIC_ORIGIN=https://other.example run_up "$T6" --check
check "PILOT_PUBLIC_ORIGIN from the shell wins over the file" out_has "DLAP_PILOT_ID_URL must equal PILOT_PUBLIC_ORIGIN (https://other.example)"

# The closing message names the real entry point (Q-517).
run_up "$T6"
check "the closing message names the tunnel and the origin" out_has "ssh -L 8443:127.0.0.1:8443 <vm>        then open https://localhost:8443"
check "the closing message no longer names the HTTP gateway" bash -c '! grep -qF "http://localhost:8081" <<<"$1"' _ "$OUT"
env_set "$T6" PILOT_TLS_BIND_ADDRESS=0.0.0.0
run_up "$T6"
check "an opened entry point is named without a tunnel" out_has "The entry point is HTTPS on 0.0.0.0:8443 of this VM. Open https://localhost:8443."
env_unset "$T6" PILOT_TLS_BIND_ADDRESS
T6B="$TMP/tree-no-tls"
overlay_tree "$T6B" keycloak
run_up "$T6B"
check "without the TLS overlay the closing message is the loopback HTTP one" out_has "ssh -L 8081:127.0.0.1:8081 <vm>"

# The identity map overlay: PILOT_IDENTITY_MAP_FILE is checked before Docker creates a directory for it.
T7="$TMP/tree-idmap"
overlay_tree "$T7" keycloak identity-map
run_up "$T7" --check
check "identity-map overlay, variable unset (the empty default): exit 0" test "$RC" -eq 0
env_set "$T7" "PILOT_IDENTITY_MAP_FILE=$T7/missing.csv"
run_up "$T7" --check
check "a missing PILOT_IDENTITY_MAP_FILE: exit 2" test "$RC" -eq 2
check "a missing PILOT_IDENTITY_MAP_FILE is named" out_has "PILOT_IDENTITY_MAP_FILE is not a readable file"
mkdir "$T7/a-directory"
env_set "$T7" "PILOT_IDENTITY_MAP_FILE=$T7/a-directory"
run_up "$T7" --check
check "a directory as PILOT_IDENTITY_MAP_FILE is refused" out_has "PILOT_IDENTITY_MAP_FILE is not a readable file"
printf '%s\n' 'GCP,a@x.example,s1' >"$T7/no-header.csv"
env_set "$T7" "PILOT_IDENTITY_MAP_FILE=$T7/no-header.csv"
run_up "$T7" --check
check "a file without the header is refused" out_has "must start with the header provider,principal,subject"
cp "$REPO_ROOT/deployment/examples/identity-map/identity-map.example.csv" "$T7/ok.csv"
env_set "$T7" "PILOT_IDENTITY_MAP_FILE=$T7/ok.csv"
run_up "$T7" --check
check "the shipped example file passes: exit 0" test "$RC" -eq 0
printf '\xEF\xBB\xBF# bom and crlf\r\nProvider, Principal, Subject\r\nANY,a@x.example,s1\r\n' >"$T7/crlf.csv"
env_set "$T7" "PILOT_IDENTITY_MAP_FILE=$T7/crlf.csv"
run_up "$T7" --check
check "a file with a BOM, CRLF and a spaced header passes (the parser accepts it)" test "$RC" -eq 0
cp "$REPO_ROOT/deployment/environments/pilot/identity-map/identity-map.csv" "$T7/empty.csv"
env_set "$T7" "PILOT_IDENTITY_MAP_FILE=$T7/empty.csv"
run_up "$T7" --check
check "the pilot's header-only default passes: exit 0" test "$RC" -eq 0
T7B="$TMP/tree-idmap-off"
overlay_tree "$T7B" keycloak
env_set "$T7B" "PILOT_IDENTITY_MAP_FILE=$T7B/missing.csv"
run_up "$T7B" --check
check "without the overlay PILOT_IDENTITY_MAP_FILE is not checked" test "$RC" -eq 0

# =================================================================================================
echo "up.sh database upgrade, real PostgreSQL"
if [ "$QUICK" -eq 1 ]; then
  echo "  SKIP  --quick"
elif [ -z "$REAL_DOCKER" ] || ! "$REAL_DOCKER" info >/dev/null 2>&1; then
  echo "  SKIP  Docker is not available"
elif ! "$REAL_DOCKER" image inspect postgres:15.1 >/dev/null 2>&1; then
  echo "  SKIP  the postgres:15.1 image is not present (docker pull postgres:15.1)"
else
  export MSYS_NO_PATHCONV=1
  hostpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
  DBS="dlap_common,da_discovery,da_catalog,da_policyengine,da_reporting,da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest"
  CONTAINER="dlap-fu2a-uptest-$$"
  pgpass="$(printf 'c%.0s' $(seq 1 24))"
  if ! cid="$("$REAL_DOCKER" create --name "$CONTAINER" -e POSTGRES_USER=dlap -e "POSTGRES_PASSWORD=$pgpass" \
        -e POSTGRES_DB=dlap -e "POSTGRES_MULTIPLE_DATABASES=$DBS" postgres:15.1 2>&1)"; then
    bad "cannot create the PostgreSQL container: $cid"
  elif ! "$REAL_DOCKER" cp "$(hostpath "$INIT_SRC")/." "$cid:/docker-entrypoint-initdb.d/" >/dev/null 2>&1; then
    bad "cannot copy the init scripts into the container"
  else
    "$REAL_DOCKER" start "$cid" >/dev/null
    T3="$TMP/tree-real"
    make_tree "$T3" real
    delta_count="$(find "$UPGRADES_SRC" -maxdepth 1 -name '*.sql' | wc -l | tr -d ' ')"

    # up.sh runs while the entrypoint is still executing the init scripts.
    FAKE_DOCKER_MODE=real FAKE_CID="$cid" PILOT_DB_WAIT_SECONDS=180 run_up "$T3"
    probes="$(grep -c 'pg_isready' <<<"$EVENTS")"
    echo "        (the first run made $probes readiness probes)"
    check "first run on a new volume: exit 0" test "$RC" -eq 0
    check "first run: the runner applied every delta ($delta_count)" out_has "summary: applied=$delta_count skipped=0 total=$delta_count"
    check "first run: the services start after the runner" before 'pg_isready' '^event up-all'
    ledger="$("$REAL_DOCKER" exec "$cid" psql -U dlap -d dlap_common -Atc 'SELECT count(*) FROM dlap_common.schema_upgrades' 2>&1)"
    check "first run: every delta is in the ledger ($ledger of $delta_count)" test "$ledger" = "$delta_count"
    check "first run: the role synchronisation ran in the container" out_has "sync-database-roles: summary:"
    roles="$("$REAL_DOCKER" exec "$cid" psql -U dlap -d dlap -Atc "SELECT string_agg(rolname, ',' ORDER BY rolname) FROM pg_roles WHERE rolname IN ('dlap_monitor','dlap_backup')" 2>&1)"
    check "first run: dlap_monitor and dlap_backup exist ($roles)" test "$roles" = "dlap_backup,dlap_monitor"

    FAKE_DOCKER_MODE=real FAKE_CID="$cid" run_up "$T3"
    check "second run: exit 0" test "$RC" -eq 0
    check "second run: nothing is applied" out_has "summary: applied=0 skipped=$delta_count total=$delta_count"

    # An applied delta must never change (ADR-0056).
    victim="$(ls "$T3"/deployment/platform/database/upgrades/*.sql | head -n 1)"
    cp "$victim" "$TMP/victim.sql"
    printf -- '-- edited after it was applied\n' >>"$victim"
    FAKE_DOCKER_MODE=real FAKE_CID="$cid" run_up "$T3"
    check "an edited delta: exit 2" test "$RC" -eq 2
    check "an edited delta: the runner says REFUSED" out_has "REFUSED"
    check "an edited delta: no service starts" no_event '^event up-all'
    cp "$TMP/victim.sql" "$victim"

    FAKE_DOCKER_MODE=real FAKE_CID="$cid" run_up "$T3"
    check "restored delta: exit 0 again" test "$RC" -eq 0

    # A pending delta older than the newest applied one is refused too (ordering rule).
    printf 'SELECT 1;\n' >"$T3/deployment/platform/database/upgrades/2000-01-01_too_old.sql"
    FAKE_DOCKER_MODE=real FAKE_CID="$cid" run_up "$T3"
    check "an out-of-order delta: exit 2" test "$RC" -eq 2
    check "an out-of-order delta: no service starts" no_event '^event up-all'
  fi
fi

# =================================================================================================
echo
echo "up-test.sh: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
