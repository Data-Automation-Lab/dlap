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
# Logical backup of every database (DR-01).
#
# One backup set per run, written to $DLAP_DR_DATA/logical/<UTC stamp>/:
#   <db>.dump       pg_dump -Fc -C of one database (custom format, with its CREATE DATABASE)
#   globals.sql     roles, memberships and role settings (pg_dumpall --globals-only, no passwords)
#   rowcounts.tsv   database, table, rows: the rows each dump carries (taken from the dump itself)
#   manifest.env    what, when, which server, the encryption key fingerprint
#   SHA256SUMS      checksums of every file above
# The set is written to a hidden .tmp- folder and renamed when it is complete, so a failed run
# leaves no half set behind. Sets beyond DLAP_BACKUP_KEEP_LOGICAL (default 14) are removed after a
# good run. The restore side is restore.sh.
#
# Usage:  backup.sh [options]
#   --databases LIST   comma or space separated (default: every connectable non-template database
#                      except `postgres`, so a database added later is covered)
#   --keep N           sets to keep (default: DLAP_BACKUP_KEEP_LOGICAL, else 14)
#   --no-prune         remove no old set
#   -h, --help         this text
#
# Environment: PGHOST, PGPORT, PGUSER, PGPASSWORD (the connecting role needs to read every database,
# and the sidecar uses the bootstrap superuser); DLAP_DR_DATA (default /backups);
# DLAP_ENCRYPTION_KEY_FINGERPRINT (optional, recorded in the manifest, see key-escrow.sh).
# Exit: 0 the set was written and checked; 1 a step failed; 2 refused (bad argument, lock held).

set -euo pipefail

# shellcheck disable=SC2034  # read by lib.sh
DR_PROG=backup
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

DBS=""
KEEP="${DLAP_BACKUP_KEEP_LOGICAL:-14}"
PRUNE=1

usage() { awk '/^# Logical backup of every database/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --databases) [ $# -ge 2 ] || dr_refuse "--databases needs a list"; DBS="$2"; shift 2 ;;
    --keep) [ $# -ge 2 ] || dr_refuse "--keep needs a number"; KEEP="$2"; shift 2 ;;
    --no-prune) PRUNE=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) dr_refuse "unknown argument: $1" ;;
  esac
done
[[ "$KEEP" =~ ^[0-9]+$ ]] && [ "$KEEP" -ge 1 ] || dr_refuse "the number of sets to keep must be a positive whole number, not '$KEEP'"

STATUS="$DR_DATA/status/logical.env"
mkdir -p "$DR_DATA/logical" "$DR_DATA/status"
dr_lock logical

STAMP="$(dr_stamp)"
SET="$DR_DATA/logical/.tmp-$STAMP"
STARTED="$(dr_now)"
STAGE="start"

# On any failure: remove the half set and record the failure for `agent.sh status`.
on_exit() {
  local rc=$?
  if [ "$rc" -ne 0 ]; then
    rm -rf "$SET"
    dr_kv_set "$STATUS" "LAST_ATTEMPT_UTC=$(dr_ts)" "LAST_ATTEMPT_RESULT=failed" \
      "LAST_ERROR=failed in step '$STAGE' (exit $rc)" || true
    dr_err "logical backup failed in step '$STAGE' (exit $rc); the failure is recorded in $STATUS"
  fi
}
trap on_exit EXIT

STAGE="connect"
dr_wait_pg 60 || dr_die "PostgreSQL does not answer at ${PGHOST:-127.0.0.1}:${PGPORT:-5432}"

if [ -z "$DBS" ]; then
  DBS="$(dr_databases | tr '\n' ' ')"
else
  DBS="$(printf '%s' "$DBS" | tr ', ' '  ')"
fi
[ -n "${DBS// /}" ] || dr_die "there is no database to back up"
for db in $DBS; do
  [[ "$db" =~ ^[A-Za-z0-9_.-]+$ ]] || DR_EXIT=2 dr_die "database name '$db' has characters this tool does not support (letters, digits, _ . - only)"
done

STAGE="space"
need=0
for db in $DBS; do
  size="$(dr_psql -d postgres -v db="$db" <<'SQL'
SELECT pg_database_size(:'db');
SQL
)"
  need=$((need + size))
done
free="$(dr_free_bytes "$DR_DATA/logical")"
# A custom-format dump is a fraction of the database; half plus a margin is a safe floor.
floor=$((need / 2 + 268435456))
if [ "$free" -lt "$floor" ]; then
  dr_die "not enough space in $DR_DATA: $(dr_mib "$free") MiB free, about $(dr_mib "$floor") MiB needed for $(dr_mib "$need") MiB of databases"
fi

mkdir -p "$SET"
: > "$SET/rowcounts.tsv"

STAGE="globals"
dr_say "dumping roles and role settings (no passwords)"
pg_dumpall --globals-only --no-role-passwords --no-password > "$SET/globals.sql"

for db in $DBS; do
  STAGE="dump $db"
  dr_say "dumping $db"
  pg_dump -Fc -C --no-password -d "$db" -f "$SET/$db.dump"
  [ -s "$SET/$db.dump" ] || dr_die "pg_dump of $db produced no data"
  STAGE="check $db"
  pg_restore -l "$SET/$db.dump" > /dev/null || dr_die "the dump of $db is not a readable archive"
  dr_dump_rowcounts "$SET/$db.dump" "$db" >> "$SET/rowcounts.tsv"
  dr_say "  $db: $(dr_mib "$(stat -c %s "$SET/$db.dump")") MiB"
done

STAGE="manifest"
pg_version="$(dr_psql -d postgres -c 'SHOW server_version')"
data_checksums="$(dr_psql -d postgres -c 'SHOW data_checksums')"
system_id="$(dr_system_id)"
ledger="$(dr_psql -d dlap_common -c 'SELECT count(*) FROM dlap_common.schema_upgrades' 2>/dev/null || echo -1)"
credentials="$(dr_psql -d da_admin -c "SELECT count(*) FROM dlap_admin.dlap_cloud_connection WHERE coalesce(encrypted_credentials, '') <> ''" 2>/dev/null || echo -1)"
keycloak="absent"
for db in $DBS; do
  if [ "$db" = "${DLAP_KEYCLOAK_DB:-keycloak}" ]; then keycloak="present"; fi
done
tables="$(wc -l < "$SET/rowcounts.tsv" | tr -d ' ')"
rows="$(awk -F'\t' '{ s += $3 } END { printf "%.0f", s }' "$SET/rowcounts.tsv")"
dump_bytes="$(cat "$SET"/*.dump | wc -c | tr -d ' ')"
{
  echo "FORMAT=dlap-dr-logical-1"
  echo "SET=$STAMP"
  echo "CREATED_UTC=$(dr_ts)"
  echo "DURATION_S=$(( $(dr_now) - STARTED ))"
  echo "PG_VERSION=$pg_version"
  echo "SYSTEM_ID=$system_id"
  echo "DATA_CHECKSUMS=$data_checksums"
  echo "DATABASES=$(printf '%s' "$DBS" | xargs | tr ' ' ',')"
  echo "DUMP_BYTES=$dump_bytes"
  echo "TABLES=$tables"
  echo "ROWS=$rows"
  echo "SCHEMA_UPGRADES=$ledger"
  echo "ENCRYPTED_CREDENTIAL_ROWS=$credentials"
  echo "KEYCLOAK_DATABASE=$keycloak"
  echo "KEY_FINGERPRINT=${DLAP_ENCRYPTION_KEY_FINGERPRINT:-unset}"
} > "$SET/manifest.env"

STAGE="checksums"
( cd "$SET" && find . -maxdepth 1 -type f ! -name SHA256SUMS -printf '%f\n' | LC_ALL=C sort | xargs sha256sum > SHA256SUMS )
dr_sums_check "$SET" SHA256SUMS || dr_die "the checksums of the new set do not verify"

STAGE="publish"
mv "$SET" "$DR_DATA/logical/$STAMP"
dr_say "logical backup $STAMP: $(printf '%s' "$DBS" | wc -w | tr -d ' ') databases, $tables tables, $rows rows, $(dr_mib "$dump_bytes") MiB"

if [ "$PRUNE" -eq 1 ]; then
  STAGE="prune"
  mapfile -t sets < <(find "$DR_DATA/logical" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*Z' -printf '%f\n' | LC_ALL=C sort)
  excess=$(( ${#sets[@]} - KEEP ))
  i=0
  while [ "$i" -lt "$excess" ]; do
    dr_say "removing old set ${sets[$i]} (keeping $KEEP)"
    rm -rf "${DR_DATA:?}/logical/${sets[$i]}" "${DR_DATA:?}/ship/.done/logical/${sets[$i]}"
    i=$((i + 1))
  done
  find "$DR_DATA/logical" -mindepth 1 -maxdepth 1 -type d -name '.tmp-*' -mmin +1440 -exec rm -rf {} + 2>/dev/null || true
fi

dr_kv_set "$STATUS" "LAST_ATTEMPT_UTC=$(dr_ts)" "LAST_ATTEMPT_RESULT=ok" "LAST_SUCCESS_UTC=$(dr_ts)" \
  "LAST_SUCCESS_SET=$STAMP" "LAST_DURATION_S=$(( $(dr_now) - STARTED ))" "LAST_BYTES=$dump_bytes" "LAST_ERROR="
STAGE="done"
