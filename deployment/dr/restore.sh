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
# Restore a logical backup set into a running PostgreSQL (DR-01).
#
# Use it on a fresh stack: start PostgreSQL alone from the stack's own init scripts (that creates the
# roles and the empty databases), run this, then start the services. For a restore to a moment between
# two backups use restore-pitr.sh instead.
#
# What it does, in order
#   1. finds the set (a folder under $DLAP_DR_DATA/logical, or one fetched from the off-host copy,
#      encrypted or not) and verifies its checksums; an encrypted set is decrypted into a work folder
#   2. compares the encryption key fingerprint in the manifest with DLAP_ENCRYPTION_KEY_FINGERPRINT of
#      this environment and refuses on a mismatch, so a wrong key is found now and not when the first
#      stored cloud credential fails to decrypt
#   3. checks that every role the set names exists on the server
#   4. prints the plan and, only with --yes-replace-databases, drops and recreates each database from
#      its dump (pg_restore -C --clean --if-exists --exit-on-error), then refreshes the statistics
#   5. counts the rows of every table of every restored database and compares them with the counts in
#      the set, which came from the dumps themselves. Any difference is a failure.
#
# Usage:  restore.sh --from SET [options]
#   --from SET                 a set folder, or `latest` for the newest under $DLAP_DR_DATA/logical
#   --databases LIST           restore only these databases (default: all in the set)
#   --yes-replace-databases    required to change anything; without it the plan is printed and the
#                              exit status is 2
#   --terminate-connections    close other sessions on the target databases first
#   --passphrase-file FILE     for an encrypted set (default DLAP_BACKUP_PASSPHRASE_FILE)
#   --ignore-key-fingerprint   go on although the key fingerprint differs or is not set
#   --verify-only              check the set (checksums, readable dumps, row counts of the dumps) and
#                              change nothing; needs no server
#   --no-analyze               skip the statistics refresh
#   --work-dir DIR             where an encrypted set is decrypted (default a new folder under /tmp)
#   --report FILE              write KEY=value lines (result, seconds, tables, rows) for tooling
#   -h, --help                 this text
#
# Environment: PGHOST, PGPORT, PGUSER, PGPASSWORD (a superuser), DLAP_DR_DATA (default /backups).
# Exit: 0 restored and every row count matches; 1 a step failed or a count differs; 2 refused
# (bad argument, set not verified, missing role, key mismatch, no consent). Exit 2 changes nothing.

set -euo pipefail

# shellcheck disable=SC2034  # read by lib.sh
DR_PROG=restore
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

FROM=""
ONLY=""
CONSENT=0
TERMINATE=0
IGNORE_FP=0
VERIFY_ONLY=0
ANALYZE=1
WORK=""
REPORT=""

usage() { awk '/^# Restore a logical backup set/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --from) [ $# -ge 2 ] || dr_refuse "--from needs a folder"; FROM="$2"; shift 2 ;;
    --databases) [ $# -ge 2 ] || dr_refuse "--databases needs a list"; ONLY="$2"; shift 2 ;;
    --yes-replace-databases) CONSENT=1; shift ;;
    --terminate-connections) TERMINATE=1; shift ;;
    --passphrase-file) [ $# -ge 2 ] || dr_refuse "--passphrase-file needs a file"; DR_PASSFILE="$2"; shift 2 ;;
    --ignore-key-fingerprint) IGNORE_FP=1; shift ;;
    --verify-only) VERIFY_ONLY=1; shift ;;
    --no-analyze) ANALYZE=0; shift ;;
    --work-dir) [ $# -ge 2 ] || dr_refuse "--work-dir needs a folder"; WORK="$2"; shift 2 ;;
    --report) [ $# -ge 2 ] || dr_refuse "--report needs a file"; REPORT="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) dr_refuse "unknown argument: $1" ;;
  esac
done
[ -n "$FROM" ] || dr_refuse "--from is required (a set folder, or latest)"

REPORTED=0
write_report() { # <result> [detail]
  [ -n "$REPORT" ] || return 0
  REPORTED=1
  {
    echo "RESULT=$1"
    echo "DETAIL=${2:-}"
    echo "SET=${SET_NAME:-}"
    echo "FINISHED_UTC=$(dr_ts)"
    echo "SECONDS=$(( $(dr_now) - T0 ))"
    echo "DATABASES=${RESTORE_DBS:-}"
    echo "TABLES=${TOTAL_TABLES:-0}"
    echo "ROWS=${TOTAL_ROWS:-0}"
    echo "RESTORE_SECONDS=${RESTORE_SECONDS:-0}"
    echo "DUMP_BYTES=${DUMP_BYTES:-0}"
  } > "$REPORT"
}

T0="$(dr_now)"
SET_NAME=""
RESTORE_DBS=""
TOTAL_TABLES=0
TOTAL_ROWS=0
RESTORE_SECONDS=0
DUMP_BYTES=0
OWN_WORK=0
# Scratch space for the row lists. The set itself is never written to: it may sit on a read-only mount.
SCR="$(mktemp -d "${TMPDIR:-/tmp}/dlap-restore.XXXXXX")"

cleanup() {
  local rc=$?
  if [ "$OWN_WORK" -eq 1 ] && [ -n "$WORK" ]; then rm -rf "$WORK"; fi
  rm -rf "$SCR"
  if [ "$rc" -ne 0 ] && [ "$REPORTED" -eq 0 ]; then write_report failed "exit $rc" || true; fi
}
trap cleanup EXIT

# --- 1. The set ---------------------------------------------------------------------------------------

if [ "$FROM" = "latest" ]; then
  name="$(find "$DR_DATA/logical" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*Z' -printf '%f\n' 2>/dev/null | LC_ALL=C sort | tail -n 1)"
  [ -n "$name" ] || dr_refuse "there is no logical backup under $DR_DATA/logical"
  FROM="$DR_DATA/logical/$name"
fi
[ -d "$FROM" ] || dr_refuse "not a folder: $FROM"
[ -f "$FROM/manifest.env" ] || dr_refuse "$FROM has no manifest.env: not a backup set"
[ "$(dr_kv "$FROM/manifest.env" FORMAT)" = "dlap-dr-logical-1" ] || dr_refuse "$FROM is not a logical backup set (FORMAT=$(dr_kv "$FROM/manifest.env" FORMAT))"
SET_NAME="$(dr_kv "$FROM/manifest.env" SET)"
SET_DBS="$(dr_kv "$FROM/manifest.env" DATABASES | tr ',' ' ')"
[ -n "${SET_DBS// /}" ] || dr_refuse "the manifest of $FROM names no database"

if [ -n "$ONLY" ]; then
  RESTORE_DBS=""
  for db in $(printf '%s' "$ONLY" | tr ',' ' '); do
    case " $SET_DBS " in *" $db "*) RESTORE_DBS="$RESTORE_DBS $db" ;; *) dr_refuse "database '$db' is not in the set (it holds: $SET_DBS)" ;; esac
  done
  RESTORE_DBS="${RESTORE_DBS# }"
else
  RESTORE_DBS="$SET_DBS"
  RESTORE_DBS="${RESTORE_DBS% }"
fi

dr_say "set $SET_NAME: databases $(printf '%s' "$SET_DBS" | xargs | tr ' ' ','), created $(dr_kv "$FROM/manifest.env" CREATED_UTC), PostgreSQL $(dr_kv "$FROM/manifest.env" PG_VERSION)"

ENCRYPTED=0
if find "$FROM" -maxdepth 1 -name '*.enc' | grep -q .; then ENCRYPTED=1; fi

if [ "$ENCRYPTED" -eq 1 ]; then
  [ -f "$FROM/SHA256SUMS.shipped" ] || dr_refuse "$FROM holds encrypted files but no SHA256SUMS.shipped: the copy is incomplete, the upload did not finish"
  dr_say "verifying the encrypted files against SHA256SUMS.shipped"
  dr_sums_check "$FROM" SHA256SUMS.shipped || dr_refuse "an encrypted file does not match SHA256SUMS.shipped: the copy is damaged"
  dr_have_passphrase || dr_refuse "the set is encrypted and there is no passphrase file at $DR_PASSFILE (use --passphrase-file)"
  if [ -z "$WORK" ]; then
    # The decrypted dumps are as large as the encrypted ones. The container's /tmp is small memory-backed
    # space, so prefer the backup volume when there is one.
    work_base="${TMPDIR:-/tmp}"
    if [ -d "$DR_DATA" ] && [ -w "$DR_DATA" ]; then mkdir -p "$DR_DATA/tmp"; work_base="$DR_DATA/tmp"; fi
    WORK="$(mktemp -d "$work_base/restore-plain.XXXXXX")"
    OWN_WORK=1
  else
    mkdir -p "$WORK"
  fi
  enc_bytes="$(find "$FROM" -maxdepth 1 -name '*.enc' -printf '%s\n' | awk '{ s += $1 } END { printf "%.0f", s }')"
  dr_require_space "$WORK" $((enc_bytes + enc_bytes / 10 + 67108864)) "the decrypted dumps (use --work-dir on a larger volume)"
  dr_say "decrypting into $WORK"
  for f in "$FROM"/*; do
    base="$(basename "$f")"
    case "$base" in
      *.enc)
        dr_decrypt < "$f" > "$WORK/${base%.enc}" || dr_refuse "cannot decrypt $base: wrong passphrase or damaged file"
        ;;
      SHA256SUMS.shipped) ;;
      *) cp "$f" "$WORK/$base" ;;
    esac
  done
  SRC="$WORK"
else
  SRC="$FROM"
fi

dr_say "verifying the checksums of the set"
dr_sums_check "$SRC" SHA256SUMS || dr_refuse "a file of the set does not match SHA256SUMS: the set is damaged"
for db in $RESTORE_DBS; do
  [ -s "$SRC/$db.dump" ] || dr_refuse "the set has no dump for $db"
  pg_restore -l "$SRC/$db.dump" > /dev/null || dr_refuse "the dump of $db is not a readable archive"
done
for db in $RESTORE_DBS; do DUMP_BYTES=$((DUMP_BYTES + $(stat -c %s "$SRC/$db.dump"))); done

# The counts in rowcounts.tsv must be what the dumps carry. Recount them from the dumps: this catches
# a set whose list and dumps disagree, which the checksums cannot (they only prove the files are the
# files that were written).
dr_say "recounting the rows of the dumps"
: > "$SCR/recount"
for db in $RESTORE_DBS; do dr_dump_rowcounts "$SRC/$db.dump" "$db" >> "$SCR/recount"; done
awk -F'\t' 'NR == FNR { want[$1] = 1; next } ($1 in want)' <(printf '%s\n' $RESTORE_DBS) "$SRC/rowcounts.tsv" > "$SCR/expected"
if ! dr_compare_rowcounts "$SCR/expected" "$SCR/recount" > "$SCR/cmp"; then
  cat "$SCR/cmp" >&2
  rm -f "$SCR/recount" "$SCR/expected" "$SCR/cmp"
  dr_refuse "rowcounts.tsv does not match the dumps of the set"
fi
rm -f "$SCR/recount" "$SCR/cmp"
EXPECTED="$SCR/expected"
expected_tables="$(wc -l < "$EXPECTED" | tr -d ' ')"
expected_rows="$(awk -F'\t' '{ s += $3 } END { printf "%.0f", s }' "$EXPECTED")"
dr_say "the set holds $expected_tables tables and $expected_rows rows in $(printf '%s' "$RESTORE_DBS" | wc -w | tr -d ' ') database(s)"

if [ "$VERIFY_ONLY" -eq 1 ]; then
  rm -f "$EXPECTED"
  dr_say "set verified; nothing was restored (--verify-only)"
  write_report verified-only
  exit 0
fi

# --- 2. The key ---------------------------------------------------------------------------------------

set_fp="$(dr_kv "$FROM/manifest.env" KEY_FINGERPRINT)"
here_fp="${DLAP_ENCRYPTION_KEY_FINGERPRINT:-}"
if [ -z "$here_fp" ]; then
  if [ "$IGNORE_FP" -eq 1 ]; then
    dr_warn "DLAP_ENCRYPTION_KEY_FINGERPRINT is not set here; the key is not checked (--ignore-key-fingerprint)"
  elif [ "$set_fp" = "unset" ] || [ -z "$set_fp" ]; then
    dr_warn "neither the set nor this environment records a key fingerprint; the key is not checked"
  else
    rm -f "$EXPECTED"
    dr_refuse "the set was taken with key fingerprint $set_fp and DLAP_ENCRYPTION_KEY_FINGERPRINT is not set here. Set it (key-escrow.sh fingerprint) or pass --ignore-key-fingerprint."
  fi
elif [ "$set_fp" = "unset" ] || [ -z "$set_fp" ]; then
  dr_warn "the set records no key fingerprint, so the key of this environment cannot be checked against it"
elif [ "$set_fp" != "$here_fp" ]; then
  if [ "$IGNORE_FP" -eq 1 ]; then
    dr_warn "key fingerprint differs (set $set_fp, here $here_fp); going on because of --ignore-key-fingerprint. Stored cloud credentials will not decrypt."
  else
    rm -f "$EXPECTED"
    dr_refuse "key fingerprint differs: the set needs $set_fp and this environment has $here_fp. Restore the original DLAP_ENCRYPTION_KEY from escrow, or pass --ignore-key-fingerprint and re-enter every cloud connection."
  fi
else
  dr_say "key fingerprint $here_fp matches the set"
fi

# --- 3. Roles and plan --------------------------------------------------------------------------------

dr_wait_pg 60 || { rm -f "$EXPECTED"; dr_die "PostgreSQL does not answer at ${PGHOST:-127.0.0.1}:${PGPORT:-5432}"; }

if [ -f "$SRC/globals.sql" ]; then
  mapfile -t need_roles < <(sed -n 's/^CREATE ROLE \(.*\);$/\1/p' "$SRC/globals.sql" | tr -d '"')
  mapfile -t have_roles < <(dr_psql -d postgres -c 'SELECT rolname FROM pg_roles')
  missing=()
  for r in "${need_roles[@]}"; do
    found=0
    for h in "${have_roles[@]}"; do if [ "$h" = "$r" ]; then found=1; break; fi; done
    if [ "$found" -eq 0 ]; then missing+=("$r"); fi
  done
  if [ "${#missing[@]}" -gt 0 ]; then
    rm -f "$EXPECTED"
    dr_refuse "the server lacks ${#missing[@]} role(s) the set refers to: ${missing[*]}. Start PostgreSQL from the stack's init scripts first, or create them (globals.sql in the set lists them; it holds no passwords)."
  fi
fi

dr_say "plan: drop and recreate $(printf '%s' "$RESTORE_DBS" | xargs | tr ' ' ',') on ${PGHOST:-127.0.0.1}:${PGPORT:-5432}"
for db in $RESTORE_DBS; do
  state="$(dr_psql -d postgres -v db="$db" <<'SQL'
SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db')
            THEN (SELECT pg_size_pretty(pg_database_size(:'db')) || ', ' || count(*) || ' session(s)'
                    FROM pg_stat_activity WHERE datname = :'db' AND pid <> pg_backend_pid())
            ELSE 'absent' END;
SQL
)"
  dr_say "  $db: now $state"
done
if [ "$CONSENT" -ne 1 ]; then
  rm -f "$EXPECTED"
  dr_refuse "nothing was changed. Add --yes-replace-databases to drop and recreate the databases above from the set."
fi

dr_lock logical 3600

for db in $RESTORE_DBS; do
  sessions="$(dr_psql -d postgres -v db="$db" <<'SQL'
SELECT count(*) FROM pg_stat_activity WHERE datname = :'db' AND pid <> pg_backend_pid();
SQL
)"
  if [ "$sessions" -gt 0 ]; then
    if [ "$TERMINATE" -eq 1 ]; then
      dr_warn "closing $sessions session(s) on $db"
      dr_psql -d postgres -v db="$db" > /dev/null <<'SQL'
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = :'db' AND pid <> pg_backend_pid();
SQL
    else
      rm -f "$EXPECTED"
      dr_refuse "$sessions other session(s) are connected to $db. Stop the services first, or pass --terminate-connections."
    fi
  fi
done

# --- 4. Restore ---------------------------------------------------------------------------------------

TR="$(dr_now)"
for db in $RESTORE_DBS; do
  dr_say "restoring $db"
  pg_restore --no-password -d postgres -C --clean --if-exists --exit-on-error "$SRC/$db.dump"
done
if [ "$ANALYZE" -eq 1 ]; then
  for db in $RESTORE_DBS; do vacuumdb --no-password --analyze-only -d "$db" > /dev/null; done
fi
RESTORE_SECONDS=$(( $(dr_now) - TR ))
dr_say "restored in ${RESTORE_SECONDS}s ($(dr_mib "$DUMP_BYTES") MiB of dumps)"

# --- 5. Compare ---------------------------------------------------------------------------------------

dr_say "counting the rows of every restored table"
ACTUAL="$SCR/actual"
: > "$ACTUAL"
for db in $RESTORE_DBS; do dr_target_rowcounts "$db" >> "$ACTUAL"; done
status=0
dr_compare_rowcounts "$EXPECTED" "$ACTUAL" > "$SCR/result" || status=1
summary="$(grep '^SUMMARY' "$SCR/result" || true)"
grep -v '^SUMMARY' "$SCR/result" >&2 || true
TOTAL_TABLES="$(printf '%s' "$summary" | sed -n 's/.*tables=\([0-9]*\).*/\1/p')"
TOTAL_ROWS="$(printf '%s' "$summary" | sed -n 's/.*rows=\([0-9]*\).*/\1/p')"
rm -f "$EXPECTED" "$ACTUAL" "$SCR/result"

if [ "$status" -ne 0 ]; then
  write_report mismatch "$summary"
  dr_die "ROW COUNTS DIFFER from the set ($summary). The databases were restored but do not match the backup."
fi
write_report ok "$summary"
dr_say "RESTORE VERIFIED: $summary, restore ${RESTORE_SECONDS}s, total $(( $(dr_now) - T0 ))s"
