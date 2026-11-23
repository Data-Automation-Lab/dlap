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
# The named tests of the database role package (SEC-12: Q-176, Q-247, Q-297, Q-449, Q-455).
#
#   S-nn  static: one rule for the password variable in the init script, the sync script and the generator;
#         the compose files hand every role its variable; gen-db-passwords.sh; hygiene of the new files
#   I-nn  the init script against a real PostgreSQL 15.1 (docker): one password per role from the environment,
#         the development fallback when it is unset, the attributes of dlap_monitor and dlap_backup, and no
#         password in the server log
#   X-nn  the Postgres exporter (the image the compose files pin) connects as dlap_monitor and reports pg_up 1
#   B-nn  the backup agent (deployment/dr/agent.sh) runs as dlap_backup: logical backup, base backup, WAL slot,
#         max_slot_wal_keep_size, status
#   Y-nn  sync-database-roles.sh on a volume from before SEC-12 (the platform roles missing, the old passwords)
#
# Usage:  test-database-roles.sh [--quick]     --quick runs the S tests only (bash is the only requirement)
# Needs Docker and the images postgres:15.1 and prometheuscommunity/postgres-exporter:v0.15.0 for I, X, B, Y;
# without Docker those are reported as SKIP, which is not a pass (exit 3).
# Exit: 0 every test passed; 1 a test failed; 3 nothing failed but some tests could not run.

set -uo pipefail
export MSYS_NO_PATHCONV=1

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_DIR="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$DB_DIR/../../.." && pwd)"
INIT="$DB_DIR/init-scripts"
SYNC="$DB_DIR/upgrades/sync-database-roles.sh"
GEN="$DB_DIR/gen-db-passwords.sh"
DEV_COMPOSE="$REPO/deployment/environments/dev/docker-compose.yml"
MICRO_COMPOSE="$REPO/deployment/environments/dev/docker-compose.microservices.yml"
PILOT_DIR="$REPO/deployment/environments/pilot"
DR_DIR="$REPO/deployment/dr"
PG_IMAGE="postgres:15.1"
EXPORTER_IMAGE="prometheuscommunity/postgres-exporter:v0.15.0"
QUICK=0
[ "${1:-}" = "--quick" ] && QUICK=1

PASSED=0
FAILED=0
SKIPPED=0
ok()   { PASSED=$((PASSED + 1)); echo "  ok    $*"; }
bad()  { FAILED=$((FAILED + 1)); echo "  FAIL  $*" >&2; }
skip() { SKIPPED=$((SKIPPED + 1)); echo "  SKIP  $*"; }
check() {
  local d="$1" err
  shift
  err="$(mktemp)"
  if "$@" > /dev/null 2> "$err"; then ok "$d"; else bad "$d"; tail -n 4 "$err" | cut -c1-300 | sed 's/^/        /' >&2; fi
  rm -f "$err"
}
hostpath() { if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

TMP="$(mktemp -d)"
RUN_ID="sec12-$$-$RANDOM"
CONTAINERS=()
NETWORK=""
cleanup() {
  local c
  # start_pg runs in a command substitution, so the CONTAINERS array does not see its containers: remove by name.
  for c in $(docker ps -a --filter "name=$RUN_ID" --format '{{.Names}}' 2> /dev/null); do docker rm -f "$c" > /dev/null 2>&1; done
  for c in "${CONTAINERS[@]:-}"; do [ -n "$c" ] && docker rm -f "$c" > /dev/null 2>&1; done
  [ -n "$NETWORK" ] && docker network rm "$NETWORK" > /dev/null 2>&1
  rm -rf "$TMP"
}
trap cleanup EXIT

DATABASES="dlap_common da_discovery da_catalog da_policyengine da_reporting da_autolabel da_autoarchival da_autodelete da_autocompliance da_admin da_logingest"
variable_of() { local n="${1^^}"; printf '%s_DB_PASSWORD' "${n//[^A-Z0-9]/_}"; }

# =====================================================================================================
echo "== S static checks"

# S-01 the rule that turns a database name into its variable is the same in the three scripts.
rule_ok() {
  local db expected got
  for db in $DATABASES; do
    expected="$(variable_of "$db")"
    # The init script is sourced in a subshell: it runs set -e and set -u, which must not reach this script.
    # POSTGRES_MULTIPLE_DATABASES is unset, so only its functions load.
    # shellcheck disable=SC1090
    got="$(unset POSTGRES_MULTIPLE_DATABASES; source "$INIT/00-create-multiple-databases.sh" > /dev/null; password_variable "$db")"
    [ "$got" = "$expected" ] || { echo "init script: $db -> $got, expected $expected" >&2; return 1; }
  done
  # The generator lists the same eleven names, then the two platform roles.
  local listed wanted
  listed="$(bash "$GEN" --list | tr '\n' ' ')"
  wanted="$(for db in $DATABASES; do variable_of "$db"; printf ' '; done)DLAP_MONITOR_DB_PASSWORD DLAP_BACKUP_DB_PASSWORD "
  [ "$listed" = "$wanted" ] || { echo "generator: [$listed] expected [$wanted]" >&2; return 1; }
  # The sync script derives its names with the same rule.
  grep -q 'printf .%s_DB_PASSWORD. "${name//\[^A-Z0-9\]/_}"' "$SYNC" || { echo "sync script rule changed" >&2; return 1; }
}
check "S-01 the init script, the sync script and the generator share one rule for <DATABASE>_DB_PASSWORD" rule_ok

# S-02 the database list of the compose file is the list of the generator.
list_ok() {
  local compose_list
  compose_list="$(grep -o 'POSTGRES_MULTIPLE_DATABASES=[^ ]*' "$DEV_COMPOSE" | head -n 1 | cut -d= -f2 | tr ',' ' ')"
  [ "$(echo $compose_list | tr ' ' '\n' | sort | tr '\n' ' ')" = "$(echo $DATABASES | tr ' ' '\n' | sort | tr '\n' ' ')" ] \
    || { echo "compose: [$compose_list]" >&2; return 1; }
}
check "S-02 the databases of the compose file are the eleven the tests and the generator know" list_ok

# S-03 the dev PostgreSQL service passes every variable; the default is empty, so the init script's fallback applies.
compose_vars_ok() {
  local v
  for v in $(bash "$GEN" --list); do
    grep -qF -- "      - $v=\${$v:-}" "$DEV_COMPOSE" || { echo "dev compose does not pass $v with an empty default" >&2; return 1; }
  done
}
check "S-03 the dev compose passes all thirteen password variables to PostgreSQL, empty when unset" compose_vars_ok

# S-04 each service gets the password of its own role, and the quickstart default is the init script's fallback.
service_ok() {
  python3 - "$(hostpath "$MICRO_COMPOSE")" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
services = {"da-discovery": "DA_DISCOVERY", "da-catalog": "DA_CATALOG", "da-policyengine": "DA_POLICYENGINE",
            "da-reporting": "DA_REPORTING", "da-autolabel": "DA_AUTOLABEL", "da-autoarchival": "DA_AUTOARCHIVAL",
            "da-autodelete": "DA_AUTODELETE", "da-logingest": "DA_LOGINGEST", "da-autocompliance": "DA_AUTOCOMPLIANCE",
            "da-admin-service": "DA_ADMIN"}
blocks = re.split(r"\n  (?=[a-z][a-z0-9-]*:\n)", text)
seen = {}
for block in blocks:
    name = block.split(":", 1)[0].strip()
    if name in services:
        var = services[name] + "_DB_PASSWORD"
        want = "${%s:-%s_pass}" % (var, services[name].lower())
        for key in ("SPRING_DATASOURCE_PASSWORD", "DB_PASS"):
            line = "      - %s=%s" % (key, want)
            if line not in block:
                sys.exit("%s: missing %s" % (name, line))
        seen[name] = True
missing = set(services) - set(seen)
sys.exit("not found: %s" % missing if missing else 0)
PY
}
check "S-04 each of the ten services reads the password of its own role (SPRING_DATASOURCE_PASSWORD and DB_PASS)" service_ok

# S-05 the exporter and the backup agent use the platform roles and not the superuser.
roles_ok() {
  python3 - "$(hostpath "$DEV_COMPOSE")" "$(hostpath "$PILOT_DIR/docker-compose.pilot.backup.yml")" <<'PY'
import sys
dev = open(sys.argv[1], encoding="utf-8").read()
backup = open(sys.argv[2], encoding="utf-8").read()
exporter = dev.split("  postgres-exporter:", 1)[1].split("\n\n", 1)[0]
assert "DATA_SOURCE_USER=dlap_monitor" in exporter, "exporter user"
assert "DATA_SOURCE_PASS=${DLAP_MONITOR_DB_PASSWORD:-dlap_monitor_pass}" in exporter, "exporter password"
assert "POSTGRES_PASSWORD" not in exporter and "DATA_SOURCE_USER=dlap\n" not in exporter, "superuser in the exporter"
assert "      - PGUSER=dlap_backup\n" in backup, "backup user"
assert "PGPASSWORD=${DLAP_BACKUP_DB_PASSWORD:-dlap_backup_pass}" in backup, "backup password"
assert "POSTGRES_PASSWORD" not in backup.split("environment:", 1)[1], "superuser password in the backup agent"
PY
}
check "S-05 postgres-exporter connects as dlap_monitor and the backup agent as dlap_backup, neither with the superuser password" roles_ok

# S-06 the TLS overlay's exporter inherits the monitor role (it sets the URI only).
tls_ok() {
  ! grep -qE 'DATA_SOURCE_(USER|PASS)' "$PILOT_DIR/docker-compose.pilot.tls.yml"
}
check "S-06 the pilot TLS overlay adds no credential of its own to the exporter" tls_ok

# S-07 the pilot refuses to start without any of the thirteen passwords or POSTGRES_PASSWORD.
pilot_required_ok() {
  local v base="$PILOT_DIR/docker-compose.pilot.base.yml"
  for v in POSTGRES_PASSWORD $(bash "$GEN" --list); do
    grep -qF -- "      - $v=\${$v:?" "$base" || { echo "pilot base overlay does not require $v" >&2; return 1; }
  done
}
check "S-07 the pilot base overlay requires POSTGRES_PASSWORD and all thirteen role passwords (no dev fallback)" pilot_required_ok

# S-08 gen-db-passwords.sh: fills, keeps, replaces placeholders, never prints a value.
gen_ok() {
  local f="$TMP/gen.env" out v n
  printf 'POSTGRES_PASSWORD=keep-me\nDA_CATALOG_DB_PASSWORD=CHANGE_ME_x\nDA_ADMIN_DB_PASSWORD=already-real-value\nOTHER=1' > "$f"
  out="$(bash "$GEN" "$f")" || return 1
  grep -q '^POSTGRES_PASSWORD=keep-me$' "$f" && grep -q '^OTHER=1$' "$f" && grep -q '^DA_ADMIN_DB_PASSWORD=already-real-value$' "$f" || { echo "an existing value changed" >&2; return 1; }
  n=0
  for v in $(bash "$GEN" --list); do
    grep -qE "^$v=[0-9a-f]{48}$|^$v=already-real-value$" "$f" || { echo "$v not generated" >&2; return 1; }
    n=$((n + 1))
  done
  [ "$n" -eq 13 ] || return 1
  grep -q 'CHANGE_ME' "$f" && { echo "a placeholder survived" >&2; return 1; }
  # A second run changes nothing, and no value reaches the output.
  local before after
  before="$(cat "$f")"
  out2="$(bash "$GEN" "$f")" || return 1
  after="$(cat "$f")"
  [ "$before" = "$after" ] || { echo "the second run changed the file" >&2; return 1; }
  echo "$out$out2" | grep -qE '[0-9a-f]{48}|already-real-value|keep-me' && { echo "a value was printed" >&2; return 1; }
  # The thirteen generated values are all different.
  [ "$(grep -E '_DB_PASSWORD=' "$f" | cut -d= -f2 | sort -u | wc -l | tr -d ' ')" = 13 ] || { echo "values repeat" >&2; return 1; }
  return 0
}
check "S-08 gen-db-passwords.sh fills the thirteen variables, replaces placeholders, keeps real values, is idempotent and prints no value" gen_ok

# S-09 hygiene: Apache header, no dash characters, LF, executable bit.
hygiene_ok() {
  local f dash1 dash2
  dash1="$(printf '\342\200\224')"
  dash2="$(printf '\342\200\223')"
  for f in "$INIT/00-create-multiple-databases.sh" "$INIT/01-create-single-database.sh" "$SYNC" "$GEN" "${BASH_SOURCE[0]}"; do
    head -n 30 "$f" | grep -q 'Licensed to the Apache Software Foundation' || { echo "no header: $f" >&2; return 1; }
    grep -q -e "$dash1" -e "$dash2" "$f" && { echo "dash in $f" >&2; return 1; }
    [ "$(python3 -c "import sys; print(open(sys.argv[1],'rb').read().count(bytes([13])))" "$(hostpath "$f")")" = 0 ] || { echo "CR in $f" >&2; return 1; }
    bash -n "$f" || return 1
  done
}
check "S-09 the new and changed scripts carry the Apache header, parse, have LF endings and no em or en dash" hygiene_ok

if [ "$QUICK" -eq 1 ]; then
  echo "== I X B Y skipped (--quick)"
  echo
  echo "test-database-roles.sh: $PASSED passed, $FAILED failed, $SKIPPED skipped"
  [ "$FAILED" -eq 0 ] || exit 1
  exit 0
fi

if ! command -v docker > /dev/null 2>&1 || ! docker info > /dev/null 2>&1; then
  skip "I/X/B/Y need Docker"
  echo
  echo "test-database-roles.sh: $PASSED passed, $FAILED failed, $SKIPPED skipped"
  [ "$FAILED" -eq 0 ] || exit 1
  exit 3
fi
docker image inspect "$PG_IMAGE" > /dev/null 2>&1 || docker pull "$PG_IMAGE" > /dev/null 2>&1
docker image inspect "$EXPORTER_IMAGE" > /dev/null 2>&1 || docker pull "$EXPORTER_IMAGE" > /dev/null 2>&1

# =====================================================================================================
# A throw-away PostgreSQL built from the real init scripts, the way the compose files start it.

NETWORK="$RUN_ID-net"
docker network create "$NETWORK" > /dev/null

# start_pg <name> <env var=value>...: creates the container with the init scripts copied in, starts it on the
# network, and waits until the init scripts have finished (a TCP listener exists only after they did).
# log_statement=ddl is the dev setting that would log a CREATE USER with its password.
start_pg() {
  local name="$RUN_ID-$1" env=() kv
  shift
  for kv in "$@"; do env+=(-e "$kv"); done
  docker create --name "$name" --network "$NETWORK" -e POSTGRES_USER=dlap -e POSTGRES_PASSWORD="su-$RANDOM-$RANDOM" \
    -e POSTGRES_DB=dlap -e "POSTGRES_MULTIPLE_DATABASES=${DATABASES// /,}" "${env[@]}" "$PG_IMAGE" \
    postgres -c log_statement=ddl > /dev/null || return 1
  CONTAINERS+=("$name")
  docker cp "$(hostpath "$INIT")/." "$name:/docker-entrypoint-initdb.d/" > /dev/null || return 1
  docker start "$name" > /dev/null || return 1
  local waited=0
  until docker exec "$name" pg_isready -q -h 127.0.0.1 -U dlap -d dlap_common > /dev/null 2>&1; do
    waited=$((waited + 1))
    [ "$waited" -gt 600 ] && { echo "PostgreSQL $name did not come up" >&2; docker logs "$name" 2>&1 | tail -n 20 >&2; return 1; }
    sleep 1
  done
  # The 03 demo seed runs in the same init sequence; the TCP listener means all scripts are done.
  printf '%s' "$name"
}

# sql <container> <user> <password> <database> <statement>: runs as that role over the container's own
# non-loopback address, where pg_hba.conf asks for a password (loopback is trust in this image).
ip_of() { docker inspect -f '{{(index .NetworkSettings.Networks "'"$NETWORK"'").IPAddress}}' "$1"; }
sql_as() {
  local c="$1" user="$2" password="$3" database="$4" statement="$5"
  docker exec -e PGPASSWORD="$password" "$c" psql -X -q -At -v ON_ERROR_STOP=1 -h "$(ip_of "$c")" -U "$user" -d "$database" -c "$statement"
}
sql_su() { docker exec "$1" psql -X -q -At -v ON_ERROR_STOP=1 -U dlap -d "${3:-dlap}" -c "$2"; }
login_ok()  { sql_as "$1" "$2" "$3" "$4" 'SELECT 1' > /dev/null 2>&1; }
login_bad() { ! login_ok "$@"; }

# Passwords of the first container: one distinct value per role, none of them the published default.
declare -A PW
for db in $DATABASES; do PW[$db]="pw-$db-$RANDOM$RANDOM"; done
PW[monitor]="pw-monitor-$RANDOM$RANDOM"
PW[backup]="pw-backup-$RANDOM$RANDOM"

ENV_ARGS=()
for db in $DATABASES; do ENV_ARGS+=("$(variable_of "$db")=${PW[$db]}"); done
ENV_ARGS+=("DLAP_MONITOR_DB_PASSWORD=${PW[monitor]}" "DLAP_BACKUP_DB_PASSWORD=${PW[backup]}")

echo "== I the init script against $PG_IMAGE"
C1="$(start_pg env "${ENV_ARGS[@]}")" || { bad "I-00 the PostgreSQL container with passwords from the environment started"; C1=""; }
if [ -n "$C1" ]; then
  ok "I-00 the init script ran to the end with thirteen passwords in the environment"
  all_roles_ok() { local db; for db in $DATABASES; do login_ok "$C1" "${db}_user" "${PW[$db]}" "$db" || { echo "no login for ${db}_user" >&2; return 1; }; done; }
  check "I-01 each service role logs in with the password of its own variable" all_roles_ok
  no_default_ok() { local db; for db in $DATABASES; do login_bad "$C1" "${db}_user" "${db}_pass" "$db" || { echo "${db}_user still accepts ${db}_pass" >&2; return 1; }; done; }
  check "I-02 no service role accepts its published <database>_pass when a password was given" no_default_ok
  check "I-03 dlap_monitor logs in with DLAP_MONITOR_DB_PASSWORD and not with dlap_monitor_pass" bash -c "$(declare -f login_ok login_bad sql_as ip_of); NETWORK='$NETWORK'; login_ok '$C1' dlap_monitor '${PW[monitor]}' dlap && login_bad '$C1' dlap_monitor dlap_monitor_pass dlap"
  check "I-04 dlap_backup logs in with DLAP_BACKUP_DB_PASSWORD and not with dlap_backup_pass" bash -c "$(declare -f login_ok login_bad sql_as ip_of); NETWORK='$NETWORK'; login_ok '$C1' dlap_backup '${PW[backup]}' dlap && login_bad '$C1' dlap_backup dlap_backup_pass dlap"

  attrs() { sql_su "$C1" "SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolcanlogin FROM pg_roles WHERE rolname = '$1'"; }
  check "I-05 dlap_monitor is a plain login role: no superuser, createdb, createrole or replication" test "$(attrs dlap_monitor)" = "f|f|f|f|t"
  check "I-06 dlap_backup has REPLICATION and nothing else of rolsuper, createdb, createrole" test "$(attrs dlap_backup)" = "f|f|f|t|t"
  member() { sql_su "$C1" "SELECT string_agg(g.rolname, ',' ORDER BY g.rolname) FROM pg_auth_members m JOIN pg_roles g ON g.oid = m.roleid JOIN pg_roles u ON u.oid = m.member WHERE u.rolname = '$1'"; }
  check "I-07 dlap_monitor is a member of pg_monitor and of nothing else" test "$(member dlap_monitor)" = "pg_monitor"
  check "I-08 dlap_backup is a member of pg_read_all_data and of nothing else" test "$(member dlap_backup)" = "pg_read_all_data"

  # What the monitor role can and cannot do.
  check "I-09 dlap_monitor reads the statistics views (pg_stat_activity, pg_stat_database, pg_settings)" \
    sql_as "$C1" dlap_monitor "${PW[monitor]}" dlap "SELECT count(*) FROM pg_stat_activity; SELECT count(*) FROM pg_stat_database; SELECT count(*) FROM pg_settings"
  denied() { ! sql_as "$@" > /dev/null 2>&1; }
  check "I-10 dlap_monitor cannot read a table of a service schema" denied "$C1" dlap_monitor "${PW[monitor]}" dlap_common "SELECT count(*) FROM dlap_common.dlap_assets"
  check "I-11 dlap_monitor cannot create a table, a role or a database" bash -c "
    $(declare -f denied sql_as ip_of); NETWORK='$NETWORK'
    denied '$C1' dlap_monitor '${PW[monitor]}' dlap 'CREATE TABLE public.x (i int)' &&
    denied '$C1' dlap_monitor '${PW[monitor]}' dlap 'CREATE ROLE x_role' &&
    denied '$C1' dlap_monitor '${PW[monitor]}' dlap 'CREATE DATABASE x_db'"

  # What the backup role can and cannot do.
  check "I-12 dlap_backup reads every table (pg_read_all_data): the assets of dlap_common" \
    sql_as "$C1" dlap_backup "${PW[backup]}" dlap_common "SELECT count(*) FROM dlap_common.dlap_assets"
  check "I-13 dlap_backup cannot write (INSERT, CREATE TABLE) or create a role" bash -c "
    $(declare -f denied sql_as ip_of); NETWORK='$NETWORK'
    denied '$C1' dlap_backup '${PW[backup]}' dlap_common 'CREATE TABLE public.x (i int)' &&
    denied '$C1' dlap_backup '${PW[backup]}' dlap 'CREATE ROLE x_role' &&
    denied '$C1' dlap_backup '${PW[backup]}' dlap 'ALTER SYSTEM SET max_connections = 10'"
  check "I-14 dlap_backup can set max_slot_wal_keep_size (the one parameter the agent tunes) and reload" bash -c "
    $(declare -f sql_as ip_of); NETWORK='$NETWORK'
    sql_as '$C1' dlap_backup '${PW[backup]}' postgres \"ALTER SYSTEM SET max_slot_wal_keep_size = '4GB'\" &&
    sql_as '$C1' dlap_backup '${PW[backup]}' postgres 'SELECT pg_reload_conf()' &&
    sql_as '$C1' dlap_backup '${PW[backup]}' postgres 'SELECT system_identifier FROM pg_control_system()' &&
    sql_as '$C1' dlap_backup '${PW[backup]}' postgres 'SELECT timeline_id FROM pg_control_checkpoint()'"
  sql_su "$C1" "ALTER SYSTEM RESET max_slot_wal_keep_size" > /dev/null 2>&1
  sql_su "$C1" "SELECT pg_reload_conf()" > /dev/null 2>&1

  # The server log: log_statement=ddl is on, and a CREATE USER would be logged with its password.
  log_ok() {
    local logs db
    logs="$(docker logs "$C1" 2>&1)"
    for db in $DATABASES; do
      grep -qF -- "${PW[$db]}" <<< "$logs" && { echo "the password of $db is in the log" >&2; return 1; }
    done
    grep -qF -- "${PW[monitor]}" <<< "$logs" && return 1
    grep -qF -- "${PW[backup]}" <<< "$logs" && return 1
    # A logged statement reads "statement: CREATE USER ..."; the bare "CREATE ROLE" that psql prints is a command tag.
    # (The CREATE ROLE that I-11 provokes as dlap_monitor is logged, by design, without a password.)
    grep -qE 'statement: *(CREATE|ALTER) +(USER|ROLE).*PASSWORD' <<< "$logs" && { echo "a role statement with a password is in the log" >&2; return 1; }
    return 0
  }
  check "I-15 no password and no CREATE USER statement reached the server log, with log_statement=ddl" log_ok
  check "I-16 the init output names the source of each password and never its value" bash -c "
    logs=\"\$(docker logs '$C1' 2>&1)\"
    grep -q \"Creating user 'da_catalog_user' (password from DA_CATALOG_DB_PASSWORD)\" <<< \"\$logs\""
fi

# The same init script with nothing in the environment: the published development defaults.
C2="$(start_pg dev)" || { bad "I-20 the PostgreSQL container without passwords started"; C2=""; }
if [ -n "$C2" ]; then
  dev_ok() { local db; for db in $DATABASES; do login_ok "$C2" "${db}_user" "${db}_pass" "$db" || { echo "no dev login for ${db}_user" >&2; return 1; }; done; }
  check "I-20 with no variable set, every service role has its published <database>_pass (the quickstart boots with no setup)" dev_ok
  check "I-21 with no variable set, dlap_monitor has dlap_monitor_pass and dlap_backup has dlap_backup_pass" bash -c "
    $(declare -f login_ok sql_as ip_of); NETWORK='$NETWORK'
    login_ok '$C2' dlap_monitor dlap_monitor_pass dlap && login_ok '$C2' dlap_backup dlap_backup_pass dlap"
  check "I-22 the init output says that the published development default applies" bash -c "docker logs '$C2' 2>&1 | grep -q \"password the published development default; set DA_CATALOG_DB_PASSWORD\""
fi

# An empty variable (a compose file passing \${X:-}) is the same as an unset one; a quote in a password is safe.
C3="$(start_pg quote DA_CATALOG_DB_PASSWORD= "DA_REPORTING_DB_PASSWORD=it's a \"test\"; DROP TABLE x;--")" || { bad "I-30 the container with an empty and a quoted password started"; C3=""; }
if [ -n "$C3" ]; then
  check "I-30 an empty variable falls back to the published default" login_ok "$C3" da_catalog_user da_catalog_pass da_catalog
  check "I-31 a password with quotes and SQL in it is stored literally and works" login_ok "$C3" da_reporting_user "it's a \"test\"; DROP TABLE x;--" da_reporting
fi

# =====================================================================================================
echo "== X the Postgres exporter connects as dlap_monitor"

if [ -n "$C1" ]; then
  if ! docker image inspect "$EXPORTER_IMAGE" > /dev/null 2>&1; then
    skip "X-nn the exporter image $EXPORTER_IMAGE is not present and could not be pulled"
  else
    EXP="$RUN_ID-exporter"
    docker run -d --name "$EXP" --network "$NETWORK" -e "DATA_SOURCE_URI=$C1:5432/dlap?sslmode=disable" \
      -e DATA_SOURCE_USER=dlap_monitor -e "DATA_SOURCE_PASS=${PW[monitor]}" "$EXPORTER_IMAGE" > /dev/null
    CONTAINERS+=("$EXP")
    metrics() { docker exec "$EXP" wget -qO- http://127.0.0.1:9187/metrics 2> /dev/null; }
    waited=0
    until metrics | grep -q '^pg_up '; do waited=$((waited + 1)); [ "$waited" -gt 30 ] && break; sleep 1; done
    metrics > "$TMP/metrics.txt"
    check "X-01 the exporter reports pg_up 1 with the monitor role's password" grep -q '^pg_up 1$' "$TMP/metrics.txt"
    check "X-02 it exports the database and connection series the alerts read (pg_stat_database_*, pg_stat_activity_count, pg_settings_max_connections)" \
      bash -c "grep -q '^pg_stat_database_deadlocks' '$TMP/metrics.txt' && grep -q '^pg_stat_activity_count' '$TMP/metrics.txt' && grep -q '^pg_settings_max_connections' '$TMP/metrics.txt'"
    seen_as="$(sql_su "$C1" "SELECT string_agg(DISTINCT usename, ',' ORDER BY usename) FROM pg_stat_activity WHERE client_addr = '$(ip_of "$EXP")'")"
    check "X-03 the server sees the exporter's connections as dlap_monitor and as nobody else (saw: $seen_as)" test "$seen_as" = "dlap_monitor"

    BAD="$RUN_ID-exporter-bad"
    docker run -d --name "$BAD" --network "$NETWORK" -e "DATA_SOURCE_URI=$C1:5432/dlap?sslmode=disable" \
      -e DATA_SOURCE_USER=dlap_monitor -e "DATA_SOURCE_PASS=dlap_monitor_pass" "$EXPORTER_IMAGE" > /dev/null
    CONTAINERS+=("$BAD")
    badmetrics() { docker exec "$BAD" wget -qO- http://127.0.0.1:9187/metrics 2> /dev/null; }
    waited=0
    until badmetrics | grep -q '^pg_up '; do waited=$((waited + 1)); [ "$waited" -gt 30 ] && break; sleep 1; done
    check "X-04 with the published default password the exporter reports pg_up 0: the role does not accept it" bash -c "$(declare -f badmetrics); BAD='$BAD'; badmetrics | grep -q '^pg_up 0\$'"
  fi
else
  skip "X-nn need the container of I-00"
fi

# =====================================================================================================
echo "== B the backup agent runs as dlap_backup"

if [ -n "$C1" ]; then
  docker exec "$C1" mkdir -p /opt/dr /tmp/dr > /dev/null 2>&1
  docker cp "$(hostpath "$DR_DIR")/." "$C1:/opt/dr/" > /dev/null 2>&1
  docker exec "$C1" chown -R postgres:postgres /tmp/dr > /dev/null 2>&1
  # As in the pilot: the agent shares the database container's network namespace and connects on 127.0.0.1, where this image's
  # pg_hba.conf allows replication connections (trust). The role's attributes and privileges still apply in full.
  IP1="127.0.0.1"
  agent() { docker exec -u postgres -e PGHOST="$IP1" -e PGPORT=5432 -e PGUSER=dlap_backup -e PGPASSWORD="${PW[backup]}" \
              -e DLAP_DR_DATA=/tmp/dr -e DLAP_WAL_CYCLE_SECONDS=30 "$C1" bash /opt/dr/agent.sh "$@"; }
  agent once logical > "$TMP/logical.out" 2>&1
  logical_rc=$?
  check "B-01 agent.sh once logical exits 0 as dlap_backup (pg_dump of every database, globals, row counts)" test "$logical_rc" -eq 0
  logical_sets="$(docker exec "$C1" bash -c 'ls /tmp/dr/logical 2>/dev/null | wc -l' | tr -d ' ')"
  check "B-02 one logical backup set exists on disk ($logical_sets)" test "${logical_sets:-0}" -ge 1
  dumps="$(docker exec "$C1" bash -c 'ls /tmp/dr/logical/*/ 2>/dev/null | grep -c "[.]dump$"' | tr -d ' ')"
  check "B-03 the set holds a dump of every database ($dumps, 11 service databases and dlap)" test "${dumps:-0}" -ge 11
  # run: starts pg_receivewal with a slot, tunes max_slot_wal_keep_size and takes the first base backup itself
  # (pg_basebackup over the replication protocol), then keeps going.
  docker exec -u postgres -d -e PGHOST="$IP1" -e PGPORT=5432 -e PGUSER=dlap_backup -e PGPASSWORD="${PW[backup]}" \
    -e DLAP_DR_DATA=/tmp/dr -e DLAP_WAL_CYCLE_SECONDS=30 "$C1" bash -c 'exec bash /opt/dr/agent.sh run > /tmp/dr/run.log 2>&1'
  waited=0
  until sql_su "$C1" "SELECT 1 FROM pg_replication_slots WHERE slot_name = 'dlap_dr' AND active" 2> /dev/null | grep -q 1; do
    waited=$((waited + 1)); [ "$waited" -gt 60 ] && break; sleep 1
  done
  check "B-05 agent.sh run creates the replication slot dlap_dr and streams into it as dlap_backup" bash -c "docker exec '$C1' psql -U dlap -d dlap -Atc \"SELECT active FROM pg_replication_slots WHERE slot_name = 'dlap_dr'\" | grep -q t"
  streamer="$(sql_su "$C1" "SELECT usename FROM pg_stat_replication LIMIT 1")"
  check "B-06 the streaming connection belongs to dlap_backup ($streamer)" test "$streamer" = "dlap_backup"
  keep="$(sql_su "$C1" "SHOW max_slot_wal_keep_size")"
  check "B-07 the agent tuned max_slot_wal_keep_size without superuser rights ($keep)" test "$keep" = "4GB"
  waited=0
  until [ -n "$(docker exec "$C1" bash -c 'ls /tmp/dr/base 2>/dev/null' | head -n 1)" ]; do
    waited=$((waited + 1)); [ "$waited" -gt 90 ] && break; sleep 2
  done
  base_sets="$(docker exec "$C1" bash -c 'ls /tmp/dr/base 2>/dev/null | wc -l' | tr -d ' ')"
  check "B-04 the running agent took a base backup as dlap_backup (pg_basebackup, base sets: $base_sets)" test "${base_sets:-0}" -ge 1
  agent status > "$TMP/status.out" 2>&1
  check "B-08 agent.sh status reports the WAL archive streaming and a base backup, as dlap_backup" \
    bash -c "grep -q '^OK    wal archive        streaming' '$TMP/status.out' && grep -q '^OK    base backup' '$TMP/status.out'"
  check "B-09 the run log holds no permission error and no superuser demand" bash -c "! docker exec '$C1' grep -qiE 'permission denied|must be superuser|not permitted' /tmp/dr/run.log"
else
  skip "B-nn need the container of I-00"
fi

# =====================================================================================================
echo "== Y sync-database-roles.sh on a volume from before SEC-12"

# C2 is a development volume: published passwords, both platform roles present. Make it look like a volume from
# before SEC-12 by dropping the two platform roles, then synchronise with new passwords.
if [ -n "$C2" ]; then
  for d in template1 postgres dlap $DATABASES; do
    sql_su "$C2" "DROP OWNED BY dlap_backup" "$d" > /dev/null 2>&1
    sql_su "$C2" "DROP OWNED BY dlap_monitor" "$d" > /dev/null 2>&1
  done
  sql_su "$C2" "DROP ROLE IF EXISTS dlap_backup" > /dev/null 2>&1
  sql_su "$C2" "DROP ROLE IF EXISTS dlap_monitor" > /dev/null 2>&1
  roles_missing="$(sql_su "$C2" "SELECT count(*) FROM pg_roles WHERE rolname IN ('dlap_monitor','dlap_backup')")"
  check "Y-00 the stage is set: the two platform roles are gone ($roles_missing left)" test "$roles_missing" = 0

  NEW_CAT="new-cat-$RANDOM$RANDOM"
  NEW_MON="new-mon-$RANDOM$RANDOM"
  sync() { docker exec -i -e DA_CATALOG_DB_PASSWORD="$NEW_CAT" -e DLAP_MONITOR_DB_PASSWORD="$NEW_MON" "$@" "$C2" bash -s -- ${SYNC_ARGS:-} < "$SYNC"; }
  SYNC_ARGS="--dry-run" docker exec -i -e DA_CATALOG_DB_PASSWORD="$NEW_CAT" -e DLAP_MONITOR_DB_PASSWORD="$NEW_MON" "$C2" bash -s -- --dry-run < "$SYNC" > "$TMP/dry.out" 2>&1
  check "Y-01 --dry-run reports what it would do and says nothing changed" grep -q 'dry run, nothing changed' "$TMP/dry.out"
  check "Y-02 --dry-run changed nothing: the old password of da_catalog_user still works, no platform role exists" bash -c "
    $(declare -f login_ok sql_su sql_as ip_of); NETWORK='$NETWORK'
    login_ok '$C2' da_catalog_user da_catalog_pass da_catalog && [ \"\$(sql_su '$C2' \"SELECT count(*) FROM pg_roles WHERE rolname IN ('dlap_monitor','dlap_backup')\")\" = 0 ]"

  docker exec -i -e DA_CATALOG_DB_PASSWORD="$NEW_CAT" -e DLAP_MONITOR_DB_PASSWORD="$NEW_MON" "$C2" bash -s -- < "$SYNC" > "$TMP/sync1.out" 2>&1
  check "Y-03 the first run exits 0 and prints a summary" bash -c "grep -q 'summary: changed' '$TMP/sync1.out'"
  check "Y-04 da_catalog_user has the new password and no longer the old one" bash -c "
    $(declare -f login_ok login_bad sql_as ip_of); NETWORK='$NETWORK'
    login_ok '$C2' da_catalog_user '$NEW_CAT' da_catalog && login_bad '$C2' da_catalog_user da_catalog_pass da_catalog"
  check "Y-05 a role whose variable is unset keeps its password (da_reporting_user still has da_reporting_pass)" login_ok "$C2" da_reporting_user da_reporting_pass da_reporting
  check "Y-06 dlap_monitor was created with the new password and the same attributes as the init script" bash -c "
    $(declare -f login_ok sql_su sql_as ip_of); NETWORK='$NETWORK'
    login_ok '$C2' dlap_monitor '$NEW_MON' dlap && [ \"\$(sql_su '$C2' \"SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication FROM pg_roles WHERE rolname = 'dlap_monitor'\")\" = 'f|f|f|f' ]"
  check "Y-07 dlap_backup was created without a password (its variable was unset): nobody can log in, and a warning said so" bash -c "
    grep -q 'dlap_backup does not exist and DLAP_BACKUP_DB_PASSWORD is not set' '$TMP/sync1.out' &&
    [ \"\$(docker exec '$C2' psql -U dlap -d dlap -Atc \"SELECT rolpassword IS NULL AND rolreplication FROM pg_authid WHERE rolname = 'dlap_backup'\")\" = t ]"

  NEW_BAK="new-bak-$RANDOM$RANDOM"
  docker exec -i -e DA_CATALOG_DB_PASSWORD="$NEW_CAT" -e DLAP_MONITOR_DB_PASSWORD="$NEW_MON" -e DLAP_BACKUP_DB_PASSWORD="$NEW_BAK" "$C2" bash -s -- --quiet < "$SYNC" > "$TMP/sync2.out" 2>&1
  check "Y-08 a second run sets the backup password, keeps the others, and the backup role can dump and use replication" bash -c "
    $(declare -f login_ok sql_su sql_as ip_of); NETWORK='$NETWORK'
    login_ok '$C2' dlap_backup '$NEW_BAK' dlap && login_ok '$C2' dlap_monitor '$NEW_MON' dlap && login_ok '$C2' da_catalog_user '$NEW_CAT' da_catalog &&
    sql_as '$C2' dlap_backup '$NEW_BAK' dlap_common 'SELECT count(*) FROM dlap_common.dlap_assets' > /dev/null &&
    sql_as '$C2' dlap_backup '$NEW_BAK' postgres 'SELECT system_identifier FROM pg_control_system()' > /dev/null"
  check "Y-09 no password reached the output of either run" bash -c "! grep -qF -e '$NEW_CAT' -e '$NEW_MON' -e '$NEW_BAK' '$TMP/sync1.out' '$TMP/sync2.out' '$TMP/dry.out'"
  check "Y-10 no password and no role statement reached the server log (log_statement=ddl is on)" bash -c "! docker logs '$C2' 2>&1 | grep -qE -e '$NEW_CAT' -e '$NEW_MON' -e '$NEW_BAK' -e 'statement: *(ALTER|CREATE) +(ROLE|USER)'"

  # --dev-defaults: a quickstart volume from before SEC-12 gets working platform roles with the published passwords.
  for d in template1 postgres dlap $DATABASES; do
    sql_su "$C2" "DROP OWNED BY dlap_backup" "$d" > /dev/null 2>&1
    sql_su "$C2" "DROP OWNED BY dlap_monitor" "$d" > /dev/null 2>&1
  done
  sql_su "$C2" "DROP ROLE IF EXISTS dlap_backup" > /dev/null 2>&1
  sql_su "$C2" "DROP ROLE IF EXISTS dlap_monitor" > /dev/null 2>&1
  sql_su "$C2" "ALTER ROLE dlap PASSWORD 'stale-superuser-pw'" > /dev/null 2>&1
  docker exec -i "$C2" bash -s -- --quiet --dev-defaults < "$SYNC" > "$TMP/sync3.out" 2>&1
  check "Y-11 --dev-defaults creates the missing platform roles with dlap_monitor_pass and dlap_backup_pass" bash -c "
    $(declare -f login_ok sql_as ip_of); NETWORK='$NETWORK'
    login_ok '$C2' dlap_monitor dlap_monitor_pass dlap && login_ok '$C2' dlap_backup dlap_backup_pass dlap"
  SU_PW="$(docker exec "$C2" printenv POSTGRES_PASSWORD)"
  check "Y-12 the bootstrap superuser's password follows POSTGRES_PASSWORD of the container (the stale one stops working)" bash -c "
    $(declare -f login_ok login_bad sql_as ip_of); NETWORK='$NETWORK'
    login_ok '$C2' dlap '$SU_PW' dlap && login_bad '$C2' dlap stale-superuser-pw dlap"
fi

echo
echo "test-database-roles.sh: $PASSED passed, $FAILED failed, $SKIPPED skipped"
[ "$FAILED" -eq 0 ] || exit 1
[ "$SKIPPED" -eq 0 ] || exit 3
exit 0
