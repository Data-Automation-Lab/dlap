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
# Ledgered one-shot upgrade runner for the shared dlap_common database (ADR-0056).
#
# Applies every dated delta in this directory (YYYY-MM-DD_name.sql) that the database has not
# had yet, in ascending file-name order, and records each one in dlap_common.schema_upgrades
# with the SHA-256 of its content and the time it was applied. The init scripts only run on an
# empty volume (docker-entrypoint-initdb.d), so this is the only path that moves an existing
# dlap_common database forward.
#
# Rules the runner enforces:
#   - Idempotent: a file already in the ledger is skipped. Running twice applies nothing.
#   - Tamper-evident: a file whose content differs from its recorded checksum is REFUSED (exit 2)
#     and nothing is applied. An applied delta is never edited; add a new one instead.
#   - Ordered: a pending file that sorts before the newest applied file is REFUSED (exit 2), because
#     deltas may redefine the same CHECK constraint and a late older one would undo a newer one.
#     --allow-out-of-order overrides that after you have read the files.
#   - Stop on first failure: a delta that fails (psql ON_ERROR_STOP) is not recorded, later files
#     are not attempted, and the exit code is 1. Fix the cause and re-run.
#   - A delta is applied and then recorded in two steps. A crash between them leaves it unrecorded,
#     and the next run applies it again. That is safe because every delta is idempotent.
#   - One runner at a time. Two concurrent runs cannot both record a file (primary key), but both
#     may apply it. Serialise runs in the caller (a single Helm hook Job, one up.sh).
#
# Usage:
#   bash run-upgrades.sh [--dry-run] [--allow-out-of-order] [--dir DIR] [--verbose]
#
#   --dry-run              Verify checksums and ordering, print what would be applied, change nothing
#                          (the ledger table is not created either). Exit 2 on a refusal, so it is
#                          also a pre-flight check.
#   --allow-out-of-order   Apply pending files that sort before the newest applied file.
#   --dir DIR              Delta directory (default: the directory of this script).
#   --verbose              Print psql output for every file, not only for a failure.
#
# Connection (use a role that owns the dlap_common objects, or a superuser):
#   DLAP_COMMON_DB_URL   libpq URI or conninfo for the dlap_common database. A leading "jdbc:" is
#                        dropped, so the JDBC URL a service uses works if it carries no
#                        JDBC-only parameters. Without it, psql uses PGHOST, PGPORT, PGUSER,
#                        PGPASSWORD and PGDATABASE (default dlap_common).
#                        Keep the password out of the URL on a shared host (it shows in the process
#                        list); set PGPASSWORD instead.
#   DLAP_PSQL            The psql command, default "psql". To reach a containerised database
#                        without a host client: DLAP_PSQL="docker exec -i dlap-postgresql psql -U dlap".
#                        Delta files are sent on stdin, so they need not exist in the container.
#   DLAP_UPGRADES_DIR    Same as --dir.
#
# Exit codes: 0 done (including nothing to do); 1 failure (usage, connection, SQL error);
#             2 refused (changed checksum, out-of-order file, malformed file name).
#
# Needs bash 3.2 or newer, psql, and sha256sum, shasum or openssl. Checksums ignore carriage
# returns, so a CRLF checkout of the same delta matches the recorded value.

set -euo pipefail
export LC_ALL=C

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UPGRADES_DIR="${DLAP_UPGRADES_DIR:-$SCRIPT_DIR}"
DRY_RUN=0
ALLOW_OUT_OF_ORDER=0
VERBOSE=0

NAME_PATTERN='^[0-9]{4}-[0-9]{2}-[0-9]{2}_[A-Za-z0-9_.-]+\.sql$'
CHECKSUM_PATTERN='^[0-9a-f]{64}$'

log() { printf 'run-upgrades: %s\n' "$*"; }
warn() { printf 'run-upgrades: WARNING: %s\n' "$*" >&2; }
fail() { printf 'run-upgrades: ERROR: %s\n' "$*" >&2; exit 1; }
refuse() { printf 'run-upgrades: REFUSED: %s\n' "$*" >&2; REFUSALS=$((REFUSALS + 1)); }

usage() {
  sed -n '/^# Usage:/,/^# Needs bash/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' | sed '$d'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --allow-out-of-order) ALLOW_OUT_OF_ORDER=1 ;;
    --verbose|-v) VERBOSE=1 ;;
    --dir)
      [ $# -ge 2 ] || fail "--dir needs a directory"
      UPGRADES_DIR="$2"
      shift
      ;;
    --dir=*) UPGRADES_DIR="${1#--dir=}" ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'run-upgrades: unknown argument: %s\n' "$1" >&2; usage >&2; exit 1 ;;
  esac
  shift
done

[ -d "$UPGRADES_DIR" ] || fail "delta directory not found: $UPGRADES_DIR"

# --- tools ---------------------------------------------------------------------------------
read -r -a PSQL <<< "${DLAP_PSQL:-psql}"
command -v "${PSQL[0]}" >/dev/null 2>&1 || fail "${PSQL[0]} not found (set DLAP_PSQL to reach the database)"

if command -v sha256sum >/dev/null 2>&1; then
  SHA_CMD=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
  SHA_CMD=(shasum -a 256)
elif command -v openssl >/dev/null 2>&1; then
  SHA_CMD=(openssl dgst -sha256 -r)
else
  fail "no SHA-256 tool found (need sha256sum, shasum or openssl)"
fi

# sha256_of FILE: hex digest of the file with carriage returns removed.
sha256_of() {
  local digest
  digest="$(tr -d '\r' < "$1" | "${SHA_CMD[@]}" | awk '{print $1}')"
  [[ $digest =~ $CHECKSUM_PATTERN ]] || fail "could not checksum $1"
  printf '%s' "$digest"
}

# --- connection ----------------------------------------------------------------------------
CONN="${DLAP_COMMON_DB_URL:-${PGDATABASE:-dlap_common}}"
CONN="${CONN#jdbc:}"

# psql_run [psql args]: one psql session with errors fatal. Callers redirect stdin themselves.
psql_run() { "${PSQL[@]}" -X -q -d "$CONN" -v ON_ERROR_STOP=1 "$@"; }

if ! conn_out="$(psql_run -At -c 'SELECT 1' </dev/null 2>&1)"; then
  printf '%s\n' "$conn_out" >&2
  fail "cannot connect to the dlap_common database (check DLAP_COMMON_DB_URL or the PG* variables)"
fi

# --- ledger --------------------------------------------------------------------------------
# Keep this DDL identical to the schema_upgrades block in init-scripts/02-create-common-schema.sql.
# CommonSchemaUpgradeTest compares the two.
ledger_ddl() {
  cat <<'SQL'
CREATE SCHEMA IF NOT EXISTS dlap_common;
CREATE TABLE IF NOT EXISTS dlap_common.schema_upgrades (
    file_name       VARCHAR(255)  PRIMARY KEY,
    checksum_sha256 CHAR(64)      NOT NULL,
    applied_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    applied_by      VARCHAR(128)  NOT NULL DEFAULT CURRENT_USER,
    CONSTRAINT ck_schema_upgrades_checksum CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$')
);
COMMENT ON TABLE dlap_common.schema_upgrades IS
    'Ledger of applied upgrades/*.sql deltas written by upgrades/run-upgrades.sh (ADR-0056)';
SQL
}

# ensure_ledger: create the ledger on a database that predates it. Quiet unless it fails.
ensure_ledger() {
  local out
  if ! out="$(ledger_ddl | psql_run -f - 2>&1)"; then
    printf '%s\n' "$out" >&2
    fail "could not create dlap_common.schema_upgrades (run as the database owner or a superuser)"
  fi
}

# record_applied NAME CHECKSUM: add the ledger row. The values go in as psql variables, never
# spliced into the SQL text.
record_applied() {
  printf '%s\n' "INSERT INTO dlap_common.schema_upgrades (file_name, checksum_sha256) VALUES (:'fname', :'fsum');" \
    | psql_run -v "fname=$1" -v "fsum=$2" -f -
}

LEDGER=""
if ! ledger_present="$(psql_run -At -c "SELECT to_regclass('dlap_common.schema_upgrades') IS NOT NULL" </dev/null)"; then
  fail "could not look for dlap_common.schema_upgrades"
fi
if [ "$ledger_present" = "t" ]; then
  # COLLATE "C": the runner compares names bytewise, so the ledger must sort the same way whatever
  # the database collation is (en_US ignores punctuation and would order some names differently).
  if ! LEDGER="$(psql_run -At -F '|' -c \
    'SELECT file_name, checksum_sha256 FROM dlap_common.schema_upgrades ORDER BY file_name COLLATE "C"' </dev/null)"; then
    fail "could not read dlap_common.schema_upgrades (run as its owner or a superuser)"
  fi
fi

# recorded_sum NAME: the checksum recorded for NAME, or empty when it was never applied.
recorded_sum() { printf '%s\n' "$LEDGER" | awk -F'|' -v n="$1" '$1 == n { print $2; exit }'; }

NEWEST_APPLIED=""
if [ -n "$LEDGER" ]; then
  NEWEST_APPLIED="$(printf '%s\n' "$LEDGER" | tail -n 1 | cut -d'|' -f1)"
fi

# --- verify, then apply ----------------------------------------------------------------------
FILES="$(find "$UPGRADES_DIR" -maxdepth 1 -type f -name '*.sql' | sort)"
REFUSALS=0

# Pass 1: nothing is applied until every file passes, so a refusal never leaves a half upgrade.
PENDING=()
SKIPPED=0
TOTAL=0
while IFS= read -r path <&3; do
  [ -n "$path" ] || continue
  name="$(basename "$path")"
  TOTAL=$((TOTAL + 1))
  if ! [[ $name =~ $NAME_PATTERN ]]; then
    refuse "$name is not named YYYY-MM-DD_name.sql (letters, digits, underscore, dot, hyphen)"
    continue
  fi
  recorded="$(recorded_sum "$name")"
  if [ -n "$recorded" ]; then
    current="$(sha256_of "$path")"
    if [ "$current" != "$recorded" ]; then
      refuse "$name changed since it was applied (recorded ${recorded:0:12}, file now ${current:0:12}). Never edit an applied delta; add a new one."
    else
      SKIPPED=$((SKIPPED + 1))
    fi
  else
    if [ -n "$NEWEST_APPLIED" ] && [[ $name < $NEWEST_APPLIED ]] && [ "$ALLOW_OUT_OF_ORDER" -ne 1 ]; then
      refuse "$name is pending but sorts before the newest applied delta $NEWEST_APPLIED. Rename it to a later date, or pass --allow-out-of-order after checking it does not redefine anything a newer delta set."
    else
      PENDING+=("$path")
    fi
  fi
done 3<<< "$FILES"

# A ledger row whose file is gone is only worth a warning: old deltas may be retired from the tree.
if [ -n "$LEDGER" ]; then
  while IFS='|' read -r recorded_name _ <&3; do
    [ -n "$recorded_name" ] || continue
    [ -f "$UPGRADES_DIR/$recorded_name" ] || warn "$recorded_name is in the ledger but not in $UPGRADES_DIR"
  done 3<<< "$LEDGER"
fi

if [ "$REFUSALS" -gt 0 ]; then
  printf 'run-upgrades: %s refusal(s); nothing was applied.\n' "$REFUSALS" >&2
  exit 2
fi

if [ "$DRY_RUN" -eq 1 ]; then
  for path in ${PENDING[@]+"${PENDING[@]}"}; do
    log "would apply $(basename "$path")"
  done
  log "summary: pending=${#PENDING[@]} skipped=$SKIPPED total=$TOTAL (dry run, nothing changed)"
  exit 0
fi

# Pass 2: apply and record, in order, stopping at the first failure.
APPLIED=0
if [ "${#PENDING[@]}" -gt 0 ]; then
  ensure_ledger
fi
for path in ${PENDING[@]+"${PENDING[@]}"}; do
  name="$(basename "$path")"
  sum="$(sha256_of "$path")"
  log "applying $name"
  if ! output="$(psql_run -f - < "$path" 2>&1)"; then
    printf '%s\n' "$output" >&2
    fail "$name failed and was not recorded; later files were not attempted. Fix the cause and re-run."
  fi
  if [ "$VERBOSE" -eq 1 ] && [ -n "$output" ]; then
    printf '%s\n' "$output"
  fi
  if ! output="$(record_applied "$name" "$sum" 2>&1)"; then
    printf '%s\n' "$output" >&2
    fail "$name was applied but could not be recorded (concurrent runner?). Re-run: the delta is idempotent."
  fi
  APPLIED=$((APPLIED + 1))
done

log "summary: applied=$APPLIED skipped=$SKIPPED total=$TOTAL"
