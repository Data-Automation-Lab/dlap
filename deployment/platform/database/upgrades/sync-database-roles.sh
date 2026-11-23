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
# Brings the database roles of an EXISTING PostgreSQL volume in line with the environment (SEC-12, Q-176,
# Q-247, Q-297, Q-449, Q-455). The init scripts create the roles only on an empty data directory, so a
# volume from before SEC-12 has the published <database>_pass logins and no dlap_monitor or dlap_backup
# role. This script is the one path that fixes that. It is not a ledgered delta: a delta is checksummed
# and applied once, and a password changes whenever the operator rotates it.
#
# What it does, every time it runs (idempotent):
#   - For each database in POSTGRES_MULTIPLE_DATABASES: when <DATABASE>_DB_PASSWORD is set and the role
#     <database>_user exists, sets its password. An unset variable leaves the role alone; it never
#     resets a password to the published default.
#   - Sets the password of the bootstrap superuser (POSTGRES_USER) from POSTGRES_PASSWORD when the container
#     has both: the image applies that variable only to a new data directory.
#   - Creates dlap_monitor (pg_monitor, nothing else) and dlap_backup (REPLICATION, pg_read_all_data, the
#     EXECUTE and ALTER SYSTEM grants deployment/dr/agent.sh needs) when they are missing, with the same
#     attributes as 00-create-multiple-databases.sh, and sets their passwords from DLAP_MONITOR_DB_PASSWORD
#     and DLAP_BACKUP_DB_PASSWORD. A role that has to be created while its variable is unset is created
#     without a password (nobody can log in as it) and a warning says so.
#   - Never prints a password, puts one on a command line or lets one reach the server log.
#
# The passwords are read from the environment of this process, so run the script where that environment
# is: inside the database container (the compose files hand it the *_DB_PASSWORD variables, and the init
# script reads the same ones), which is how deployment/environments/pilot/up.sh does it:
#
#   docker exec -i dlap-postgresql bash -s -- [--dry-run] < sync-database-roles.sh
#
# or on any host that reaches the server with psql and has the variables exported.
#
# Usage:
#   sync-database-roles.sh [--dry-run] [--quiet] [--dev-defaults]
#
#   --dry-run   report which roles exist and what would change, change nothing
#   --quiet     print only warnings and the summary line
#   --dev-defaults
#               a platform role that has to be created while its variable is unset gets its published
#               development password (dlap_monitor_pass, dlap_backup_pass) instead of none. For a quickstart
#               volume that predates SEC-12, so that postgres-exporter works again; never for a pilot or a demo.
#               An existing role is not touched.
#
# Connection: POSTGRES_USER (default: PGUSER, else postgres) and POSTGRES_DB (default: PGDATABASE, else
# postgres), then the usual libpq variables (PGHOST, PGPORT, PGPASSWORD). The role must be a superuser or
# have CREATEROLE with ADMIN on the roles it changes; in the container it is the bootstrap superuser.
#
# Exit: 0 done (including nothing to do); 1 a failure (connection, SQL); 2 bad usage.

set -euo pipefail

DRY_RUN=0
QUIET=0
DEV_DEFAULTS=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --quiet) QUIET=1 ;;
    --dev-defaults) DEV_DEFAULTS=1 ;;
    -h|--help) sed -n '/^# Brings the database roles/,/^# Exit:/p' "${BASH_SOURCE[0]:-$0}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "sync-database-roles: unknown argument: $arg" >&2; exit 2 ;;
  esac
done

PSQL_USER="${POSTGRES_USER:-${PGUSER:-postgres}}"
PSQL_DB="${POSTGRES_DB:-${PGDATABASE:-postgres}}"
DATABASES="${POSTGRES_MULTIPLE_DATABASES:-dlap_common,da_discovery,da_catalog,da_policyengine,da_reporting,da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest}"

CHANGED=0
WARNINGS=0
say() { if [ "$QUIET" -eq 0 ]; then printf 'sync-database-roles: %s\n' "$*"; fi; }
warn() { WARNINGS=$((WARNINGS + 1)); printf 'sync-database-roles: WARNING: %s\n' "$*" >&2; }
fail() { printf 'sync-database-roles: ERROR: %s\n' "$*" >&2; exit 1; }

# The environment variable that holds the password of a database's user (the rule of the init script).
password_variable() {
  local name="${1^^}"
  printf '%s_DB_PASSWORD' "${name//[^A-Z0-9]/_}"
}

# Opens every session: nothing of it reaches the server log, and the password is read from the
# environment of the psql process.
PRELUDE="SET log_statement = 'none';
SET log_min_error_statement = 'panic';
\\getenv pw SYNC_PASSWORD
SELECT :'pw' <> '' AS has_pw \\gset
SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role') AS present \\gset
"

# run_sql <database> <password or empty> <role>: runs the prelude and the SQL on stdin in one session.
run_sql() {
  local database="$1" password="$2" role="$3" script
  script="$(cat)"
  { printf '%s\n' "$PRELUDE"; printf '%s\n' "$script"; } \
    | SYNC_PASSWORD="$password" psql -X -q -v ON_ERROR_STOP=1 -v "role=$role" -U "$PSQL_USER" -d "$database" -f - \
    || fail "the session for role $role failed"
}

# present <role>: 0 when the role exists.
present() {
  # psql does not interpolate variables in a -c string, so the query goes in on stdin.
  [ "$(printf '%s
' "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role');"     | psql -X -q -At -U "$PSQL_USER" -d "$PSQL_DB" -v "role=$1" -f -)" = "t" ]
}

command -v psql >/dev/null 2>&1 || fail "psql not found"
psql -X -q -At -U "$PSQL_USER" -d "$PSQL_DB" -c 'SELECT 1' </dev/null >/dev/null 2>&1 \
  || fail "cannot connect as $PSQL_USER to $PSQL_DB (POSTGRES_USER, POSTGRES_DB, PGHOST, PGPORT, PGPASSWORD)"

# --- The service users --------------------------------------------------------------------------

IFS=',' read -ra DB_LIST <<< "$DATABASES"
for database in "${DB_LIST[@]}"; do
  database="$(echo "$database" | xargs)"
  [ -n "$database" ] || continue
  role="${database}_user"
  variable="$(password_variable "$database")"
  value="${!variable:-}"
  if ! present "$role"; then
    say "$role does not exist; skipped"
  elif [ -z "$value" ]; then
    say "$role: $variable is not set; password left as it is"
  elif [ "$DRY_RUN" -eq 1 ]; then
    say "$role: would set the password from $variable"
    CHANGED=$((CHANGED + 1))
  else
    run_sql "$PSQL_DB" "$value" "$role" <<'SQL'
\if :present
ALTER ROLE :"role" PASSWORD :'pw';
\endif
SQL
    say "$role: password set from $variable"
    CHANGED=$((CHANGED + 1))
  fi
done

# --- The bootstrap superuser --------------------------------------------------------------------

# The PostgreSQL image reads POSTGRES_PASSWORD only when it creates the data directory, so a volume keeps the
# password it was born with. When the container has both variables, the role takes the environment's value.
if [ -n "${POSTGRES_USER:-}" ] && [ -n "${POSTGRES_PASSWORD:-}" ]; then
  if [ "$DRY_RUN" -eq 1 ]; then
    say "$POSTGRES_USER: would set the password from POSTGRES_PASSWORD"
  else
    run_sql "$PSQL_DB" "$POSTGRES_PASSWORD" "$POSTGRES_USER" <<'SQL'
\if :present
ALTER ROLE :"role" PASSWORD :'pw';
\endif
SQL
    say "$POSTGRES_USER: password set from POSTGRES_PASSWORD"
  fi
  CHANGED=$((CHANGED + 1))
fi

# --- The platform roles -------------------------------------------------------------------------

# sync_platform_role <role> <variable>: creates the role when it is missing, with a password from the variable.
# The attributes beyond LOGIN are in the SQL of the caller (it follows on stdin).
sync_platform_role() {
  local role="$1" variable="$2" value
  value="${!variable:-}"
  if present "$role"; then
    if [ -z "$value" ]; then say "$role: exists; $variable is not set, password left as it is"; else say "$role: exists; password set from $variable"; fi
  else
    if [ -z "$value" ] && [ "$DEV_DEFAULTS" -eq 1 ]; then
      value="${role}_pass"
      say "$role: created with the published development password ${role}_pass (--dev-defaults, quickstart volumes only)"
    elif [ -z "$value" ]; then
      warn "$role does not exist and $variable is not set: it is created without a password, so nobody can log in as it. Set the variable and run this script again."
    else
      say "$role: created; password from $variable"
    fi
  fi
  if [ "$DRY_RUN" -eq 1 ]; then CHANGED=$((CHANGED + 1)); return 0; fi
  CHANGED=$((CHANGED + 1))
  run_sql "$PSQL_DB" "$value" "$role"
}

sync_platform_role dlap_monitor DLAP_MONITOR_DB_PASSWORD <<'SQL'
\if :present
ALTER ROLE :"role" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
\if :has_pw
ALTER ROLE :"role" PASSWORD :'pw';
\endif
\else
\if :has_pw
CREATE ROLE :"role" LOGIN PASSWORD :'pw' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
\else
CREATE ROLE :"role" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
\endif
\endif
GRANT pg_monitor TO :"role";
SQL

sync_platform_role dlap_backup DLAP_BACKUP_DB_PASSWORD <<'SQL'
\if :present
ALTER ROLE :"role" LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE;
\if :has_pw
ALTER ROLE :"role" PASSWORD :'pw';
\endif
\else
\if :has_pw
CREATE ROLE :"role" LOGIN PASSWORD :'pw' REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE;
\else
CREATE ROLE :"role" LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE;
\endif
\endif
GRANT pg_read_all_data TO :"role";
SELECT current_setting('server_version_num')::int >= 150000 AS pg15 \gset
\if :pg15
GRANT ALTER SYSTEM ON PARAMETER max_slot_wal_keep_size TO :"role";
\endif
SQL

# Function privileges are per database: template1 (inherited by databases created later) and postgres.
if [ "$DRY_RUN" -eq 0 ]; then
  for database in template1 postgres; do
    psql -X -q -v ON_ERROR_STOP=1 -U "$PSQL_USER" -d "$database" -c \
      'GRANT EXECUTE ON FUNCTION pg_control_system(), pg_control_checkpoint(), pg_reload_conf() TO dlap_backup' </dev/null \
      || fail "could not grant the backup role its function privileges in $database"
  done
fi

if [ "$DRY_RUN" -eq 1 ]; then
  printf 'sync-database-roles: summary: would change %s role(s), %s warning(s) (dry run, nothing changed)\n' "$CHANGED" "$WARNINGS"
else
  printf 'sync-database-roles: summary: changed %s role(s), %s warning(s)\n' "$CHANGED" "$WARNINGS"
fi
