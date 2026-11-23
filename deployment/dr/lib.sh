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
# Shared helpers for the DLAP backup and restore tools (DR-01). SOURCE this file, do not run it:
#
#   source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
#
# Conventions
#   - Every tool connects with the standard libpq variables (PGHOST, PGPORT, PGUSER, PGPASSWORD). The
#     backup sidecar sets them; a one-off container for a restore gets them from the same compose service.
#   - State lives under DLAP_DR_DATA (default /backups):
#       logical/<stamp>/   one logical backup set (pg_dump per database, row counts, checksums)
#       base/<stamp>/      one physical base backup (pg_basebackup, tar + gzip)
#       wal/<key>/         the WAL archive that pg_receivewal streams; <key> is <system id>-tl<timeline>
#       ship/              staging and markers of the off-host copy
#       status/            small KEY=value files that `agent.sh status` reads
#       .locks/            flock files
#   - Exit status: 0 success; 1 a step failed; 2 a refusal or a bad argument (nothing was changed).
#   - Nothing here prints a secret. Passphrases are read from a file, never from the command line.
#
# The tools run in the postgres:15.1 image (bash 5, GNU coreutils, openssl 1.1.1, flock, PostgreSQL
# client programs), the same image as the database, so the client and the server always match.

DR_DATA="${DLAP_DR_DATA:-/backups}"
DR_PASSFILE="${DLAP_BACKUP_PASSPHRASE_FILE:-/run/secrets/dlap_backup_passphrase}"
DR_PROG="${DR_PROG:-dr}"
DR_ITER=600000
umask 077

# --- Output -----------------------------------------------------------------------------------------

dr_ts() { date -u +%Y-%m-%dT%H:%M:%SZ; }
dr_say() { printf '%s %s: %s\n' "$(dr_ts)" "$DR_PROG" "$*"; }
dr_warn() { printf '%s %s: WARN: %s\n' "$(dr_ts)" "$DR_PROG" "$*" >&2; }
dr_err() { printf '%s %s: ERROR: %s\n' "$(dr_ts)" "$DR_PROG" "$*" >&2; }
# dr_die <message>: print and exit. DR_EXIT (default 1) is the status; 2 means "refused, nothing changed".
dr_die() { dr_err "$*"; exit "${DR_EXIT:-1}"; }
# dr_refuse <message>: a refusal or a bad argument, status 2.
dr_refuse() { dr_err "$*"; exit 2; }

# --- Time -------------------------------------------------------------------------------------------

dr_stamp() { date -u +%Y%m%dT%H%M%SZ; }
dr_now() { date -u +%s; }
# dr_epoch <ISO 8601 UTC>: seconds since the epoch, 0 when the text is empty or unreadable.
dr_epoch() { if [ -n "${1:-}" ]; then date -u -d "$1" +%s 2>/dev/null || echo 0; else echo 0; fi; }

# dr_due_daily <HH:MM UTC> <last success epoch> <now epoch>: true when the daily slot of today has
# passed and no run succeeded since it. A host that was down at the slot catches up when it returns;
# a first run (last success 0) is due at once.
dr_due_daily() {
  local at="$1" last="$2" now="$3" day sched
  [ "$last" -eq 0 ] && return 0
  day="$(date -u -d "@$now" +%Y-%m-%d)"
  sched="$(date -u -d "$day $at" +%s)"
  [ "$now" -ge "$sched" ] && [ "$last" -lt "$sched" ]
}

# dr_due_every <days> <HH:MM UTC> <last success epoch> <now epoch>: true when the last success is at
# least <days> days old (an hour of slack) and today's slot has passed; a first run is due at once.
dr_due_every() {
  local days="$1" at="$2" last="$3" now="$4" day sched
  [ "$last" -eq 0 ] && return 0
  day="$(date -u -d "@$now" +%Y-%m-%d)"
  sched="$(date -u -d "$day $at" +%s)"
  [ "$now" -ge "$sched" ] && [ $((now - last)) -ge $((days * 86400 - 3600)) ]
}

# --- Files ------------------------------------------------------------------------------------------

dr_sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

# dr_sums_check <dir> <sums file name>: verify every file the sums file lists, relative to <dir>.
dr_sums_check() {
  ( cd "$1" && if command -v sha256sum >/dev/null 2>&1; then sha256sum -c --quiet "$2"; else shasum -a 256 -c --quiet "$2"; fi )
}

# dr_free_bytes <dir>: free bytes on the file system of <dir>.
dr_free_bytes() { df -Pk "$1" | awk 'NR == 2 { printf "%.0f\n", $4 * 1024 }'; }

# dr_require_space <dir> <bytes> <what>: refuse (exit 2) when the file system of <dir> has less free space.
dr_require_space() {
  local free
  free="$(dr_free_bytes "$1")"
  if [ "$free" -lt "$2" ]; then
    dr_refuse "not enough free space in $1 for $3: $(dr_mib "$free") MiB free, $(dr_mib "$2") MiB needed"
  fi
}

# dr_mib <bytes>: whole mebibytes, rounded up.
dr_mib() { echo $(( ($1 + 1048575) / 1048576 )); }

# dr_kv <file> <KEY>: value of KEY in a KEY=value file, last assignment wins, empty when absent.
dr_kv() {
  local line
  [ -f "$1" ] || return 0
  line="$(grep -E "^$2=" "$1" | tail -n 1 || true)"
  printf '%s' "${line#*=}" | tr -d '\r'
}

# dr_kv_set <file> <KEY=value>...: rewrite the file atomically with the given keys replaced or added.
dr_kv_set() {
  local file="$1" tmp pair key
  shift
  mkdir -p "$(dirname "$file")"
  tmp="$(mktemp "$file.XXXXXX")"
  if [ -f "$file" ]; then cp "$file" "$tmp"; fi
  for pair in "$@"; do
    key="${pair%%=*}"
    grep -v -E "^${key}=" "$tmp" > "$tmp.n" || true
    printf '%s\n' "$pair" >> "$tmp.n"
    mv "$tmp.n" "$tmp"
  done
  mv "$tmp" "$file"
}

# dr_lock <name> [wait seconds]: take an exclusive lock for the life of the process, or refuse.
dr_lock() {
  mkdir -p "$DR_DATA/.locks"
  local fd
  exec {fd}>"$DR_DATA/.locks/$1.lock"
  flock -w "${2:-0}" "$fd" || DR_EXIT=2 dr_die "another $1 run holds the lock ($DR_DATA/.locks/$1.lock)"
}

# --- Encryption -------------------------------------------------------------------------------------
#
# Copies that leave the host are encrypted with openssl enc: AES-256-CBC, a key derived from the
# passphrase file by PBKDF2-HMAC-SHA256 ($DR_ITER iterations) and a random salt. That protects the
# content from whoever reads the object store. It does not authenticate it: integrity comes from the
# SHA-256 lists, and an adversary who can write to the store needs object lock or versioning to be
# stopped (docs/guides/backup-and-restore.md, "What the encryption does and does not do").

dr_have_passphrase() { [ -s "$DR_PASSFILE" ]; }

dr_encrypt() { openssl enc -aes-256-cbc -pbkdf2 -iter "$DR_ITER" -md sha256 -salt -pass "file:$DR_PASSFILE"; }
dr_decrypt() { openssl enc -d -aes-256-cbc -pbkdf2 -iter "$DR_ITER" -md sha256 -pass "file:$DR_PASSFILE"; }

# --- PostgreSQL -------------------------------------------------------------------------------------

# dr_psql <psql arguments>: quiet, unaligned, tuples only, stops at the first error.
dr_psql() { psql -X -q -At -v ON_ERROR_STOP=1 "$@"; }

# dr_wait_pg [seconds]: wait until the server accepts connections.
dr_wait_pg() {
  local limit="${1:-120}" waited=0
  until pg_isready -q -h "${PGHOST:-127.0.0.1}" -p "${PGPORT:-5432}" -U "${PGUSER:-dlap}" -d postgres; do
    waited=$((waited + 2))
    [ "$waited" -le "$limit" ] || return 1
    sleep 2
  done
}

# dr_databases: the databases to protect, one per line. DLAP_DR_DATABASES (comma or space separated)
# overrides; otherwise every connectable non-template database except the maintenance one, so a
# database added later (the Keycloak database of IAM-02) is covered without a change here.
dr_databases() {
  if [ -n "${DLAP_DR_DATABASES:-}" ]; then
    printf '%s\n' "$DLAP_DR_DATABASES" | tr ', ' '\n\n' | sed '/^$/d'
    return 0
  fi
  dr_psql -d postgres -c "SELECT datname FROM pg_database WHERE NOT datistemplate AND datallowconn AND datname <> 'postgres' ORDER BY datname"
}

# dr_system_id: the cluster's system identifier; dr_timeline: its current timeline.
dr_system_id() { dr_psql -d postgres -c "SELECT system_identifier FROM pg_control_system()"; }
dr_timeline() { dr_psql -d postgres -c "SELECT timeline_id FROM pg_control_checkpoint()"; }
# dr_archive_key: names the WAL archive directory. A restored cluster keeps its system identifier but
# starts a new timeline, and the old archive must not be extended past the fork point, so the key
# includes the timeline. When the archive had to be restarted because WAL was lost (the server
# invalidated the slot, or the slot was dropped), a generation suffix -g<N> starts a new directory: a
# receiver that finds old segments behind a new slot would ask the server for WAL it no longer has.
dr_archive_key() {
  local key gen
  key="$(printf '%s-tl%s' "$(dr_system_id)" "$(dr_timeline)")"
  gen="$(dr_kv "$DR_DATA/status/archive-generation" GENERATION)"
  if [ -n "$gen" ] && [ "$gen" -gt 0 ]; then key="$key-g$gen"; fi
  printf '%s' "$key"
}

# --- Row counts -------------------------------------------------------------------------------------
#
# The row counts of a backup come from the dump itself, not from the live database: the number of
# rows each COPY block of the archive carries. That is exact whatever was written while the dump ran,
# so a restore can be compared with it without a false alarm. Tables are named as pg_dump names them
# (schema.table, quoted only when needed), which is what format('%I.%I') produces on the target.

# dr_dump_rowcounts <dump file or -> <database>: "database<TAB>table<TAB>rows", one per COPY block.
dr_dump_rowcounts() {
  pg_restore --data-only -f - "$1" | awk -v db="$2" '
    /^COPY / { tbl = $0; sub(/^COPY /, "", tbl); sub(/ \(.*\) FROM stdin;$/, "", tbl); sub(/ FROM stdin;$/, "", tbl); n = 0; incopy = 1; next }
    incopy && $0 == "\\." { printf "%s\t%s\t%.0f\n", db, tbl, n; incopy = 0; next }
    incopy { n++ }
  '
}

# dr_target_rowcounts <database>: the same lines, counted in a live database. Ordinary and partition
# tables only (a partitioned parent has no rows of its own); tables owned by an extension are skipped
# because pg_dump does not dump their rows either.
dr_target_rowcounts() {
  dr_psql -d "$1" -F $'\t' <<'SQL'
SELECT format('SELECT %L, %L, count(*) FROM %I.%I', current_database(), format('%I.%I', n.nspname, c.relname), n.nspname, c.relname)
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind = 'r'
   AND n.nspname NOT IN ('pg_catalog', 'information_schema')
   AND n.nspname !~ '^pg_toast'
   AND n.nspname !~ '^pg_temp'
   AND NOT EXISTS (SELECT 1 FROM pg_depend d
                    WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
 ORDER BY n.nspname, c.relname
\gexec
SQL
}

# dr_compare_rowcounts <expected tsv> <actual tsv>: print one MISMATCH line per difference and a
# summary line; status 1 when anything differs. A table the dump does not list holds no rows.
dr_compare_rowcounts() {
  awk -F'\t' '
    NR == FNR { expected[$1 "\t" $2] = $3; keys[$1 "\t" $2] = 1; next }
    { actual[$1 "\t" $2] = $3; keys[$1 "\t" $2] = 1 }
    END {
      for (k in keys) {
        e = (k in expected) ? expected[k] : 0
        if (!(k in actual)) { printf "MISMATCH %s expected=%s actual=missing\n", k, e; bad++; continue }
        tables++; rows += actual[k]
        if (e + 0 != actual[k] + 0) { printf "MISMATCH %s expected=%s actual=%s\n", k, e, actual[k]; bad++ }
      }
      printf "SUMMARY tables=%d rows=%.0f mismatches=%d\n", tables, rows, bad
      exit (bad > 0)
    }' "$1" "$2" | sed 's/\t/ /g'
}

# --- Off-host copy ------------------------------------------------------------------------------------

# dr_ship_enabled: true when an off-host copy is configured.
dr_ship_enabled() { [ -n "${DLAP_BACKUP_SHIP_CMD:-}" ]; }
