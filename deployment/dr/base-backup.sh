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
# Physical base backup of the whole cluster (DR-01), the starting point of a point-in-time restore.
#
# pg_basebackup in tar format with gzip and the WAL it needs streamed along (-X stream), so a set
# restores to its own end without the archive. The WAL archive that agent.sh streams continuously
# (wal/<key>/) carries the cluster from there to any later moment, which is how the 15 minute RPO is
# met. A set is written to $DLAP_DR_DATA/base/<UTC stamp>/:
#   base.tar.gz, pg_wal.tar.gz, backup_manifest    pg_basebackup output
#   manifest.env    what, when, the WAL archive key and the first WAL segment the set needs
#   SHA256SUMS      checksums of every file above
# After a good run the newest DLAP_BACKUP_KEEP_BASE sets (default 3) are kept, and the WAL archive is
# trimmed to what the oldest kept set needs (pg_archivecleanup). The restore side is restore-pitr.sh.
#
# Usage:  base-backup.sh [options]
#   --keep N        sets to keep (default: DLAP_BACKUP_KEEP_BASE, else 3)
#   --no-prune      remove no old set and trim no WAL
#   --prune-only    only apply the retention rules, take no backup
#   -h, --help      this text
#
# Environment: PGHOST, PGPORT, PGUSER, PGPASSWORD (a role with the REPLICATION attribute),
# DLAP_DR_DATA (default /backups), DLAP_WAL_SLOT (default dlap_dr).
# Exit: 0 done; 1 a step failed; 2 refused (bad argument, lock held).

set -euo pipefail

# shellcheck disable=SC2034  # read by lib.sh
DR_PROG=base-backup
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

KEEP="${DLAP_BACKUP_KEEP_BASE:-3}"
PRUNE=1
PRUNE_ONLY=0
SLOT="${DLAP_WAL_SLOT:-dlap_dr}"

usage() { awk '/^# Physical base backup/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --keep) [ $# -ge 2 ] || dr_refuse "--keep needs a number"; KEEP="$2"; shift 2 ;;
    --no-prune) PRUNE=0; shift ;;
    --prune-only) PRUNE_ONLY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) dr_refuse "unknown argument: $1" ;;
  esac
done
[[ "$KEEP" =~ ^[0-9]+$ ]] && [ "$KEEP" -ge 1 ] || dr_refuse "the number of sets to keep must be a positive whole number, not '$KEEP'"

STATUS="$DR_DATA/status/base.env"
mkdir -p "$DR_DATA/base" "$DR_DATA/wal" "$DR_DATA/status"
dr_lock base

# prune_sets_and_wal: keep the newest $KEEP complete sets, then trim every WAL archive to what the
# oldest kept set of that archive needs. An archive no kept set refers to is removed unless it is
# the one the running cluster writes (current_key).
prune_sets_and_wal() {
  local current_key="$1" sets excess i name key start dir oldest
  mapfile -t sets < <(find "$DR_DATA/base" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*Z' -printf '%f\n' | LC_ALL=C sort)
  excess=$(( ${#sets[@]} - KEEP ))
  i=0
  while [ "$i" -lt "$excess" ]; do
    dr_say "removing old base backup ${sets[$i]} (keeping $KEEP)"
    rm -rf "${DR_DATA:?}/base/${sets[$i]}" "${DR_DATA:?}/ship/.done/base/${sets[$i]}"
    i=$((i + 1))
  done
  find "$DR_DATA/base" -mindepth 1 -maxdepth 1 -type d -name '.tmp-*' -mmin +1440 -exec rm -rf {} + 2>/dev/null || true

  mapfile -t sets < <(find "$DR_DATA/base" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*Z' -printf '%f\n' | LC_ALL=C sort)
  declare -A oldest_start=()
  for name in "${sets[@]}"; do
    key="$(dr_kv "$DR_DATA/base/$name/manifest.env" ARCHIVE_KEY)"
    start="$(dr_kv "$DR_DATA/base/$name/manifest.env" START_WAL)"
    [ -n "$key" ] && [ -n "$start" ] || continue
    if [ -z "${oldest_start[$key]:-}" ] || [[ "$start" < "${oldest_start[$key]}" ]]; then oldest_start[$key]="$start"; fi
  done
  for dir in "$DR_DATA"/wal/*/; do
    [ -d "$dir" ] || continue
    key="$(basename "$dir")"
    oldest="${oldest_start[$key]:-}"
    if [ -n "$oldest" ]; then
      pg_archivecleanup "$DR_DATA/wal/$key" "$oldest" || dr_warn "pg_archivecleanup failed for $key"
    elif [ "$key" != "$current_key" ]; then
      dr_say "removing the WAL archive $key: no kept base backup refers to it"
      rm -rf "${DR_DATA:?}/wal/$key"
    fi
  done
}

if [ "$PRUNE_ONLY" -eq 1 ]; then
  dr_wait_pg 60 || dr_die "PostgreSQL does not answer at ${PGHOST:-127.0.0.1}:${PGPORT:-5432}"
  prune_sets_and_wal "$(dr_archive_key)"
  exit 0
fi

STAMP="$(dr_stamp)"
SET="$DR_DATA/base/.tmp-$STAMP"
STARTED="$(dr_now)"
STAGE="start"

on_exit() {
  local rc=$?
  if [ "$rc" -ne 0 ]; then
    rm -rf "$SET"
    dr_kv_set "$STATUS" "LAST_ATTEMPT_UTC=$(dr_ts)" "LAST_ATTEMPT_RESULT=failed" \
      "LAST_ERROR=failed in step '$STAGE' (exit $rc)" || true
    dr_err "base backup failed in step '$STAGE' (exit $rc); the failure is recorded in $STATUS"
  fi
}
trap on_exit EXIT

STAGE="connect"
dr_wait_pg 60 || dr_die "PostgreSQL does not answer at ${PGHOST:-127.0.0.1}:${PGPORT:-5432}"
KEY="$(dr_archive_key)"

STAGE="slot"
active="$(dr_psql -d postgres -v slot="$SLOT" <<'SQL'
SELECT coalesce((SELECT active::text FROM pg_replication_slots WHERE slot_name = :'slot'), 'missing');
SQL
)"
if [ "$active" != "true" ]; then
  dr_warn "the WAL archive is not streaming (slot $SLOT: $active). This set restores to its own end only, until the archive catches up."
fi

STAGE="space"
need="$(dr_psql -d postgres -c "SELECT coalesce(sum(pg_database_size(datname)), 0) FROM pg_database")"
free="$(dr_free_bytes "$DR_DATA/base")"
floor=$((need / 2 + 268435456))
if [ "$free" -lt "$floor" ]; then
  dr_die "not enough space in $DR_DATA: $(dr_mib "$free") MiB free, about $(dr_mib "$floor") MiB needed for a cluster of $(dr_mib "$need") MiB"
fi

STAGE="pg_basebackup"
dr_say "taking a base backup of archive $KEY"
mkdir -p "$SET"
pg_basebackup -D "$SET" -Ft -z -X stream -c fast --no-password -l "dlap-$STAMP"

STAGE="check"
[ -s "$SET/base.tar.gz" ] && [ -s "$SET/pg_wal.tar.gz" ] || dr_die "pg_basebackup did not write base.tar.gz and pg_wal.tar.gz"
gzip -t "$SET/base.tar.gz" "$SET/pg_wal.tar.gz" || dr_die "a base backup archive is not a valid gzip file"
label="$(tar -xzOf "$SET/base.tar.gz" backup_label)"
start_wal="$(printf '%s\n' "$label" | sed -n 's/^START WAL LOCATION: .*(file \([0-9A-F]\{24\}\)).*$/\1/p')"
start_tli="$(printf '%s\n' "$label" | sed -n 's/^START TIMELINE: \([0-9]*\)$/\1/p')"
[ -n "$start_wal" ] && [ -n "$start_tli" ] || dr_die "backup_label does not name the start segment"

STAGE="manifest"
databases="$(dr_databases | tr '\n' ',' | sed 's/,$//')"
base_bytes="$(stat -c %s "$SET/base.tar.gz")"
wal_bytes="$(stat -c %s "$SET/pg_wal.tar.gz")"
{
  echo "FORMAT=dlap-dr-base-1"
  echo "SET=$STAMP"
  echo "CREATED_UTC=$(dr_ts)"
  echo "DURATION_S=$(( $(dr_now) - STARTED ))"
  echo "PG_VERSION=$(dr_psql -d postgres -c 'SHOW server_version')"
  echo "SYSTEM_ID=$(dr_system_id)"
  echo "ARCHIVE_KEY=$KEY"
  echo "START_WAL=$start_wal"
  echo "START_TLI=$start_tli"
  echo "DATABASES=$databases"
  echo "BASE_BYTES=$base_bytes"
  echo "WAL_BYTES=$wal_bytes"
  echo "KEY_FINGERPRINT=${DLAP_ENCRYPTION_KEY_FINGERPRINT:-unset}"
} > "$SET/manifest.env"
( cd "$SET" && find . -maxdepth 1 -type f ! -name SHA256SUMS -printf '%f\n' | LC_ALL=C sort | xargs sha256sum > SHA256SUMS )
dr_sums_check "$SET" SHA256SUMS || dr_die "the checksums of the new set do not verify"

STAGE="publish"
mv "$SET" "$DR_DATA/base/$STAMP"
dr_say "base backup $STAMP: $(dr_mib "$base_bytes") MiB, starts at segment $start_wal, archive $KEY"

if [ "$PRUNE" -eq 1 ]; then
  STAGE="prune"
  prune_sets_and_wal "$KEY"
fi

dr_kv_set "$STATUS" "LAST_ATTEMPT_UTC=$(dr_ts)" "LAST_ATTEMPT_RESULT=ok" "LAST_SUCCESS_UTC=$(dr_ts)" \
  "LAST_SUCCESS_SET=$STAMP" "LAST_DURATION_S=$(( $(dr_now) - STARTED ))" "LAST_BYTES=$((base_bytes + wal_bytes))" "LAST_ERROR="
STAGE="done"
