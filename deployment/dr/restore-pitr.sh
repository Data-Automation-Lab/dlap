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
# Point-in-time restore: prepare a PostgreSQL data directory from a base backup and the WAL archive
# (DR-01). This is how the RPO of 15 minutes is met: the base backup plus every WAL segment up to the
# moment of the disaster, including the segment that was still being written.
#
# It does not need a running server. It writes a data directory; the next start of PostgreSQL on that
# directory replays the WAL up to the target and then opens for writes on a new timeline.
#
# What it does, in order
#   1. picks the base backup (the newest one that is older than the target, or --base) and verifies it
#   2. unpacks it into --pgdata, which must be empty
#   3. copies the WAL segments of the archive, from the one the base backup starts at, into pg_wal/.
#      A segment that was still being written when the disk was lost (a .partial file) is used under
#      its final name when no complete copy exists. Encrypted and compressed copies from the off-host
#      store are decrypted and unpacked on the way. Gaps in the sequence are reported.
#      The base backup's own pg_wal/ already holds the segments it streamed, and the last of them ends at
#      the end of the backup, while the archive copy of the same name goes on to the moment of the
#      disaster. Recovery stops at the first byte of WAL it cannot read, so a copy that is shorter than
#      the archive's would end it there (Q-752). The copy with more WAL in it always wins (the archive
#      copy on a tie), which is what restore_command would do: it asks the archive first.
#   3b. reads the prepared WAL with pg_waldump from the start of the base backup, as recovery will, and
#      fails (exit 1, the directory removed again) when the reading stops before the newest archived
#      segment although the archive holds WAL beyond the base backup. A restore that would silently end
#      at the base backup is never reported as ready. --no-replay-check skips this walk.
#   4. writes the recovery target into postgresql.auto.conf and creates recovery.signal
#   5. makes the directory owned by the postgres user (uid 999) with mode 700
#
# Usage:  restore-pitr.sh --from ROOT --pgdata DIR [options]
#   --from ROOT          a folder with base/ and wal/ in it: the backup volume, or a copy fetched from
#                        the off-host store with the same layout
#   --pgdata DIR         the new data directory (empty or absent)
#   --target T           latest (default) | immediate | time:YYYY-MM-DDTHH:MM:SSZ | lsn:X/Y | name:RESTORE_POINT
#   --base SET           use this base backup (a folder name under base/) instead of choosing one
#   --system-id ID       choose among the base backups of this cluster only. A store that holds the
#                        backups of more than one cluster (the original, and the one restored from it) needs
#                        this, or --base, for a name or LSN target: only you know which one has that point.
#   --passphrase-file F  for encrypted copies (default DLAP_BACKUP_PASSPHRASE_FILE)
#   --allow-gaps         go on although the WAL sequence has a gap (recovery then ends at the gap)
#   --no-replay-check    do not read the prepared WAL with pg_waldump (step 3b). The walk reads every
#                        archived segment once, about as fast as a disk reads; skip it for a very large
#                        archive, or to restore to a target before known damage at the end of the archive.
#   --owner UID:GID      owner of the result (default: user postgres, else 999:999)
#   --report FILE        write KEY=value lines for tooling
#   -h, --help           this text
#
# Then start PostgreSQL on the directory and wait for "database system is ready to accept
# connections"; with a target, the log says where recovery stopped. docs/guides/backup-and-restore.md
# has the full procedure for the pilot stack.
# Exit: 0 the directory is ready; 1 a step failed, or the prepared WAL does not read through to the newest
# archived segment (step 3b); 2 refused (bad argument, nothing to restore from, directory not empty,
# verification failed).

set -euo pipefail

# shellcheck disable=SC2034  # read by lib.sh
DR_PROG=restore-pitr
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

FROM=""
PGDATA_DIR=""
TARGET="latest"
BASE_SET=""
SYSTEM_ID_WANTED=""
ALLOW_GAPS=0
REPLAY_CHECK=1
OWNER=""
REPORT=""

usage() { awk '/^# Point-in-time restore/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --from) [ $# -ge 2 ] || dr_refuse "--from needs a folder"; FROM="$2"; shift 2 ;;
    --pgdata) [ $# -ge 2 ] || dr_refuse "--pgdata needs a folder"; PGDATA_DIR="$2"; shift 2 ;;
    --target) [ $# -ge 2 ] || dr_refuse "--target needs a value"; TARGET="$2"; shift 2 ;;
    --base) [ $# -ge 2 ] || dr_refuse "--base needs a set name"; BASE_SET="$2"; shift 2 ;;
    --system-id) [ $# -ge 2 ] || dr_refuse "--system-id needs a system identifier"; SYSTEM_ID_WANTED="$2"; shift 2 ;;
    --passphrase-file) [ $# -ge 2 ] || dr_refuse "--passphrase-file needs a file"; DR_PASSFILE="$2"; shift 2 ;;
    --allow-gaps) ALLOW_GAPS=1; shift ;;
    --no-replay-check) REPLAY_CHECK=0; shift ;;
    --owner) [ $# -ge 2 ] || dr_refuse "--owner needs UID:GID"; OWNER="$2"; shift 2 ;;
    --report) [ $# -ge 2 ] || dr_refuse "--report needs a file"; REPORT="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) dr_refuse "unknown argument: $1" ;;
  esac
done
[ -n "$FROM" ] || dr_refuse "--from is required"
[ -n "$PGDATA_DIR" ] || dr_refuse "--pgdata is required"
[ -d "$FROM/base" ] || dr_refuse "$FROM has no base/ folder"

T0="$(dr_now)"
write_report() { # <result> [detail]
  [ -n "$REPORT" ] || return 0
  {
    echo "RESULT=$1"
    echo "DETAIL=${2:-}"
    echo "BASE_SET=${BASE_SET:-}"
    echo "ARCHIVE_KEY=${KEY:-}"
    echo "TARGET=$TARGET"
    echo "SEGMENTS=${SEG_COUNT:-0}"
    echo "FIRST_SEGMENT=${FIRST_SEG:-}"
    echo "LAST_SEGMENT=${LAST_SEG:-}"
    echo "USED_PARTIAL=${USED_PARTIAL:-0}"
    echo "REPLACED_BASE_SEGMENTS=${REPLACED_COUNT:-0}"
    echo "REPLAY_CHECK=${REPLAY_RESULT:-skipped}"
    echo "BASE_START_LSN=${BASE_START_LSN:-}"
    echo "REPLAY_STOP_LSN=${REPLAY_STOP_LSN:-}"
    echo "NEWEST_SEGMENT=${NEWEST_DATA_SEG:-}"
    echo "SECONDS=$(( $(dr_now) - T0 ))"
  } > "$REPORT"
}
on_exit() {
  local rc=$?
  if [ "$rc" -ne 0 ]; then write_report failed "exit $rc" || true; fi
}
trap on_exit EXIT

# --- Target -------------------------------------------------------------------------------------------

TARGET_TIME_EPOCH=0
case "$TARGET" in
  latest|immediate) ;;
  time:*)
    t="${TARGET#time:}"
    [[ "$t" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || dr_refuse "a time target looks like time:2026-10-19T10:15:00Z, not '$TARGET'"
    TARGET_TIME_EPOCH="$(date -u -d "$t" +%s)" || dr_refuse "cannot read the time '$t'"
    ;;
  lsn:*) [[ "${TARGET#lsn:}" =~ ^[0-9A-Fa-f]+/[0-9A-Fa-f]+$ ]] || dr_refuse "an LSN target looks like lsn:0/3000060, not '$TARGET'" ;;
  name:*) [[ "${TARGET#name:}" =~ ^[A-Za-z0-9_.-]+$ ]] || dr_refuse "a restore point name may hold letters, digits and _ . - only" ;;
  *) dr_refuse "unknown target '$TARGET' (latest, immediate, time:..., lsn:..., name:...)" ;;
esac

# --- Base backup --------------------------------------------------------------------------------------

complete_set() { [ -f "$1/SHA256SUMS" ] || [ -f "$1/SHA256SUMS.shipped" ]; }

if [ -z "$BASE_SET" ]; then
  mapfile -t candidates < <(find "$FROM/base" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*Z' -printf '%f\n' | LC_ALL=C sort -r)
  eligible=()
  for c in "${candidates[@]}"; do
    complete_set "$FROM/base/$c" || continue
    if [ -n "$SYSTEM_ID_WANTED" ] && [ "$(dr_kv "$FROM/base/$c/manifest.env" SYSTEM_ID)" != "$SYSTEM_ID_WANTED" ]; then continue; fi
    if [ "$TARGET_TIME_EPOCH" -gt 0 ]; then
      created="$(dr_epoch "$(dr_kv "$FROM/base/$c/manifest.env" CREATED_UTC)")"
      [ "$created" -lt "$TARGET_TIME_EPOCH" ] || continue
    fi
    eligible+=("$c")
  done
  [ "${#eligible[@]}" -gt 0 ] || dr_refuse "no complete base backup under $FROM/base${SYSTEM_ID_WANTED:+ for system $SYSTEM_ID_WANTED}${TARGET_TIME_EPOCH:+ that is older than the target}"
  # A name or LSN target says nothing about time, so with the backups of several clusters in the store the
  # newest one may belong to the wrong cluster. Do not guess.
  case "$TARGET" in
    name:*|lsn:*)
      declare -A systems=()
      for c in "${eligible[@]}"; do systems[$(dr_kv "$FROM/base/$c/manifest.env" SYSTEM_ID)]=1; done
      if [ "${#systems[@]}" -gt 1 ]; then
        {
          echo "restore-pitr: the store holds base backups of ${#systems[@]} clusters and $TARGET names a point that only one of them has:"
          for c in "${eligible[@]}"; do
            printf '    %s  created %s  system %s  archive %s\n' "$c" "$(dr_kv "$FROM/base/$c/manifest.env" CREATED_UTC)" \
              "$(dr_kv "$FROM/base/$c/manifest.env" SYSTEM_ID)" "$(dr_kv "$FROM/base/$c/manifest.env" ARCHIVE_KEY)"
          done
          echo "  Choose with --system-id (the cluster that was running when the restore point was made) or --base."
        } >&2
        exit 2
      fi
      ;;
  esac
  BASE_SET="${eligible[0]}"
fi
BDIR="$FROM/base/$BASE_SET"
[ -f "$BDIR/manifest.env" ] || dr_refuse "$BDIR has no manifest.env"
[ "$(dr_kv "$BDIR/manifest.env" FORMAT)" = "dlap-dr-base-1" ] || dr_refuse "$BDIR is not a base backup set"
complete_set "$BDIR" || dr_refuse "$BDIR is incomplete (no SHA256SUMS): the copy did not finish"
KEY="$(dr_kv "$BDIR/manifest.env" ARCHIVE_KEY)"
START_WAL="$(dr_kv "$BDIR/manifest.env" START_WAL)"
[ -n "$KEY" ] && [ -n "$START_WAL" ] || dr_refuse "the manifest of $BDIR lacks ARCHIVE_KEY or START_WAL"
dr_say "base backup $BASE_SET (created $(dr_kv "$BDIR/manifest.env" CREATED_UTC), archive $KEY, starts at segment $START_WAL), target $TARGET"

ENC=""
if [ -f "$BDIR/base.tar.gz.enc" ]; then
  ENC=".enc"
  [ -f "$BDIR/SHA256SUMS.shipped" ] || dr_refuse "$BDIR holds encrypted files but no SHA256SUMS.shipped: the copy is incomplete"
  dr_sums_check "$BDIR" SHA256SUMS.shipped || dr_refuse "an encrypted file of $BDIR does not match SHA256SUMS.shipped: the copy is damaged"
  dr_have_passphrase || dr_refuse "the base backup is encrypted and there is no passphrase file at $DR_PASSFILE (use --passphrase-file)"
else
  [ -s "$BDIR/base.tar.gz" ] && [ -s "$BDIR/pg_wal.tar.gz" ] || dr_refuse "$BDIR lacks base.tar.gz or pg_wal.tar.gz"
  dr_sums_check "$BDIR" SHA256SUMS || dr_refuse "a file of $BDIR does not match SHA256SUMS: the backup is damaged"
fi
extra="$(find "$BDIR" -maxdepth 1 -type f -name '*.tar.gz*' ! -name 'base.tar.gz*' ! -name 'pg_wal.tar.gz*' | head -n 1)"
[ -z "$extra" ] || dr_refuse "$BDIR contains a tablespace archive ($(basename "$extra")); tablespaces are not used by DLAP and are not supported here"

# unpack <file name without .enc> <target dir>
unpack() {
  if [ -n "$ENC" ]; then dr_decrypt < "$BDIR/$1$ENC" | tar -xz -C "$2"; else tar -xzf "$BDIR/$1" -C "$2"; fi
}

if [ -e "$PGDATA_DIR" ] && [ -n "$(ls -A "$PGDATA_DIR" 2>/dev/null)" ]; then
  dr_refuse "$PGDATA_DIR is not empty. A restore never writes over a data directory."
fi
# A lower bound of what the restore writes: twice the compressed base backup (data compresses at least that
# well) and the archived WAL as it is stored.
target_fs="$PGDATA_DIR"
[ -d "$target_fs" ] || target_fs="$(dirname "$PGDATA_DIR")"
base_bytes="$(find "$BDIR" -maxdepth 1 -name '*.tar.gz*' -printf '%s\n' | awk '{ s += $1 } END { printf "%.0f", s }')"
wal_bytes="$(find "$FROM/wal/$KEY" -maxdepth 1 -type f -printf '%s\n' 2> /dev/null | awk '{ s += $1 } END { printf "%.0f", s }')"
dr_require_space "$target_fs" $((base_bytes * 2 + wal_bytes)) "the data directory"
mkdir -p "$PGDATA_DIR/pg_wal"
dr_say "unpacking the base backup into $PGDATA_DIR"
unpack base.tar.gz "$PGDATA_DIR"
unpack pg_wal.tar.gz "$PGDATA_DIR/pg_wal"
[ -f "$PGDATA_DIR/backup_label" ] && [ -f "$PGDATA_DIR/PG_VERSION" ] || dr_die "the base backup did not unpack to a data directory"

# --- WAL ----------------------------------------------------------------------------------------------

WDIR="$FROM/wal/$KEY"
SEG_COUNT=0
FIRST_SEG=""
LAST_SEG=""
USED_PARTIAL=0
REPLACED_COUNT=0
BASE_LAST_SEG=""
NEWEST_DATA_SEG=""
BASE_START_LSN=""
REPLAY_STOP_LSN=""
REPLAY_RESULT="skipped"
if [ -d "$WDIR" ]; then
  seg_bytes="$(pg_controldata "$PGDATA_DIR" | sed -n 's/^Bytes per WAL segment: *//p')"
  per_log=$(( 4294967296 / ${seg_bytes:-16777216} ))
  # Names present in the archive, without any suffix, and which form each has.
  declare -A form=()   # name -> complete | partial
  declare -A path=()
  while IFS= read -r f; do
    b="$(basename "$f")"
    n="${b%%.*}"
    [[ "$n" =~ ^[0-9A-F]{24}$ ]] || continue
    case "$b" in
      "$n"|"$n.gz"|"$n.gz.enc") form[$n]="complete"; path[$n]="$f" ;;
      "$n.partial"|"$n.partial.gz"|"$n.partial.gz.enc") if [ "${form[$n]:-}" != "complete" ]; then form[$n]="partial"; path[$n]="$f"; fi ;;
    esac
  done < <(find "$WDIR" -maxdepth 1 -type f | LC_ALL=C sort)

  # nonzero <file>: the number of bytes that are not zero. A segment is padded to its full size, and two copies
  # of one segment hold the same WAL up to the end of the shorter one and zeros after it, so the copy with more
  # non-zero bytes is the longer one.
  nonzero() { tr -d '\000' < "$1" | wc -c | tr -d ' '; }

  # place <name> <source>: put one segment into pg_wal/ under its plain name. When the base backup already left
  # a copy of that name there (the segments it streamed), the copy with more WAL in it stays, the archive's on
  # a tie. Sets PLACE_RESULT to new, replaced (the archive copy was longer), same or kept (the base copy was longer).
  place() {
    local n="$1" src="$2" out="$PGDATA_DIR/pg_wal/$1" tmp="$PGDATA_DIR/pg_wal/.dlap-place.$1" a b
    case "$src" in
      *.gz.enc) dr_have_passphrase || dr_refuse "WAL copies are encrypted and there is no passphrase file at $DR_PASSFILE"
                dr_decrypt < "$src" | gunzip -c > "$tmp" ;;
      *.gz) gunzip -c < "$src" > "$tmp" ;;
      *) cp "$src" "$tmp" ;;
    esac
    if [ ! -e "$out" ]; then
      mv "$tmp" "$out"
      PLACE_RESULT=new
      return 0
    fi
    a="$(nonzero "$tmp")"
    b="$(nonzero "$out")"
    if [ "$a" -ge "$b" ]; then
      mv -f "$tmp" "$out"
      if [ "$a" -gt "$b" ]; then
        PLACE_RESULT=replaced
        REPLACED_COUNT=$((REPLACED_COUNT + 1))
        dr_say "segment $n: the archive copy holds more WAL than the base backup's ($a against $b non-zero bytes) and replaces it"
      else
        PLACE_RESULT=same
      fi
    else
      rm -f "$tmp"
      PLACE_RESULT=kept
      dr_warn "segment $n: the base backup's copy holds more WAL than the archive's ($b against $a non-zero bytes) and is kept"
    fi
  }

  # The segments the base backup streamed are in pg_wal/ now; remember the last one.
  BASE_LAST_SEG="$(find "$PGDATA_DIR/pg_wal" -maxdepth 1 -type f -printf '%f\n' | grep -E '^[0-9A-F]{24}$' | LC_ALL=C sort | tail -n 1 || true)"
  PLACE_RESULT=new
  prev=-1
  gaps=()
  while IFS= read -r n; do
    [[ "$n" < "$START_WAL" ]] && continue
    place "$n" "${path[$n]}"
    SEG_COUNT=$((SEG_COUNT + 1))
    [ -n "$FIRST_SEG" ] || FIRST_SEG="$n"
    LAST_SEG="$n"
    if [ "${form[$n]}" = "partial" ] && [ "$PLACE_RESULT" != "kept" ]; then USED_PARTIAL=$((USED_PARTIAL + 1)); fi
    cur=$(( 16#${n:8:8} * per_log + 16#${n:16:8} ))
    if [ "$prev" -ge 0 ] && [ "$cur" -ne $((prev + 1)) ]; then gaps+=("before $n"); fi
    prev="$cur"
  done < <(printf '%s\n' "${!form[@]}" | LC_ALL=C sort)
  # History files tell recovery which timeline it is on.
  while IFS= read -r f; do
    b="$(basename "$f")"
    case "$b" in
      *.history) cp "$f" "$PGDATA_DIR/pg_wal/$b" ;;
      *.history.gz) gunzip -c < "$f" > "$PGDATA_DIR/pg_wal/${b%.gz}" ;;
      *.history.gz.enc) dr_decrypt < "$f" | gunzip -c > "$PGDATA_DIR/pg_wal/${b%.gz.enc}" ;;
    esac
  done < <(find "$WDIR" -maxdepth 1 -type f -name '*.history*')

  partial_note=""; if [ "$USED_PARTIAL" -gt 0 ]; then partial_note=", $USED_PARTIAL taken from a partial file"; fi
  dr_say "WAL: $SEG_COUNT segment(s) from ${FIRST_SEG:-none} to ${LAST_SEG:-none}$partial_note"
  if [ "${#gaps[@]}" -gt 0 ]; then
    dr_err "the WAL sequence has ${#gaps[@]} gap(s): ${gaps[*]}"
    if [ "$ALLOW_GAPS" -ne 1 ] && [ "$TARGET" != "immediate" ]; then
      rm -rf "${PGDATA_DIR:?}"/* "${PGDATA_DIR:?}"/.[!.]* 2>/dev/null || true
      dr_refuse "recovery would end at the first gap and lose everything after it. The data directory was removed again. Use an older target, an older base backup, or --allow-gaps."
    fi
  fi

  # replay_check: read the prepared pg_wal/ the way recovery will, from the start of the base backup, and
  # fail when the reading stops before the newest archived segment that holds WAL. Without this a restore
  # whose base copy of a segment is shorter than the archive's would exit 0 and replay nothing (Q-752).
  # Sets REPLAY_RESULT to ok, nothing-newer or failed.
  replay_check() {
    local newest="" n tli msg stop hi lo idx_stop idx_new
    # A segment holds WAL when more than its page header (40 bytes at most) is non-zero.
    while IFS= read -r n; do
      [[ "$n" < "$START_WAL" ]] && break
      if [ "$(nonzero "$PGDATA_DIR/pg_wal/$n")" -gt 40 ]; then newest="$n"; break; fi
    done < <(printf '%s\n' "${!form[@]}" | LC_ALL=C sort -r)
    NEWEST_DATA_SEG="$newest"
    if [ -z "$newest" ] || { [ -n "$BASE_LAST_SEG" ] && [[ ! "$newest" > "$BASE_LAST_SEG" ]] && [ "$REPLACED_COUNT" -eq 0 ]; }; then
      REPLAY_RESULT="nothing-newer"
      return 0
    fi
    BASE_START_LSN="$(sed -n 's/^START WAL LOCATION: \([0-9A-Fa-f]*\/[0-9A-Fa-f]*\) .*/\1/p' "$PGDATA_DIR/backup_label")"
    [ -n "$BASE_START_LSN" ] || { REPLAY_RESULT="skipped-no-start-lsn"; dr_warn "backup_label has no START WAL LOCATION: the replay check was skipped"; return 0; }
    tli=$((16#${newest:0:8}))
    # pg_waldump -q prints errors only. Reading always ends with an error at the end of the WAL; the last
    # position in that message is the first byte recovery cannot read.
    msg="$(pg_waldump -q -p "$PGDATA_DIR/pg_wal" -t "$tli" -s "$BASE_START_LSN" 2>&1 > /dev/null || true)"
    # pg_waldump prints an LSN as upper-case hex after a space and before a colon, a comma or the end of the line.
    stop="$(printf '%s\n' "$msg" | grep -oE ' [0-9A-F]{1,8}/[0-9A-F]{1,8}($|[:, ])' | tail -n 1 | tr -d ' :,' || true)"
    if [ -z "$stop" ]; then
      if [ -z "$msg" ]; then REPLAY_RESULT="ok"; return 0; fi
      REPLAY_RESULT="failed"
      rm -rf "${PGDATA_DIR:?}"/* "${PGDATA_DIR:?}"/.[!.]* 2>/dev/null || true
      dr_die "pg_waldump cannot read the prepared WAL from $BASE_START_LSN ($(printf '%s' "$msg" | head -n 1 | cut -c1-200)). The data directory was removed again."
    fi
    REPLAY_STOP_LSN="$stop"
    hi="${stop%%/*}"
    lo="${stop##*/}"
    idx_stop=$(( 16#$hi * per_log + 16#$lo / ${seg_bytes:-16777216} ))
    idx_new=$(( 16#${newest:8:8} * per_log + 16#${newest:16:8} ))
    if [ "$idx_stop" -ge "$idx_new" ]; then
      REPLAY_RESULT="ok"
      dr_say "replay check: the WAL reads from $BASE_START_LSN through the newest archived segment $newest (reading ends at $stop)"
      return 0
    fi
    REPLAY_RESULT="failed"
    rm -rf "${PGDATA_DIR:?}"/* "${PGDATA_DIR:?}"/.[!.]* 2>/dev/null || true
    dr_die "recovery would stop at $stop, before the newest archived segment $newest: the archive holds WAL that recovery cannot reach and would replay nothing of it. The data directory was removed again. Check the archive copy of the segment that holds $stop, use an older base backup, or --no-replay-check."
  }
  if [ "$TARGET" = "immediate" ]; then
    REPLAY_RESULT="skipped-immediate"
  elif [ "$REPLAY_CHECK" -ne 1 ]; then
    REPLAY_RESULT="skipped-by-option"
  elif [ "${#gaps[@]}" -gt 0 ]; then
    REPLAY_RESULT="skipped-gaps"
  elif ! command -v pg_waldump > /dev/null 2>&1; then
    REPLAY_RESULT="skipped-no-pg_waldump"
    dr_warn "pg_waldump is not installed: the replay check was skipped"
  else
    replay_check
  fi
elif [ "$TARGET" != "immediate" ]; then
  dr_warn "there is no WAL archive for $KEY under $FROM/wal: the restore ends at the end of the base backup"
fi

# --- Recovery settings --------------------------------------------------------------------------------

{
  echo ""
  echo "# Written by restore-pitr.sh (DR-01) at $(dr_ts) from base backup $BASE_SET."
  echo "# Every WAL segment the recovery needs was copied into pg_wal/. PostgreSQL 15 refuses archive recovery"
  echo "# without a restore_command, so this one says that nothing more is available."
  echo "restore_command = 'false'"
  case "$TARGET" in
    immediate) echo "recovery_target = 'immediate'" ;;
    time:*) echo "recovery_target_time = '$(date -u -d "${TARGET#time:}" '+%Y-%m-%d %H:%M:%S+00')'" ;;
    lsn:*) echo "recovery_target_lsn = '${TARGET#lsn:}'" ;;
    name:*) echo "recovery_target_name = '${TARGET#name:}'" ;;
  esac
  if [ "$TARGET" != "latest" ]; then echo "recovery_target_action = 'promote'"; fi
} >> "$PGDATA_DIR/postgresql.auto.conf"
: > "$PGDATA_DIR/recovery.signal"

# --- Ownership ----------------------------------------------------------------------------------------

if [ -z "$OWNER" ]; then
  if id postgres > /dev/null 2>&1; then OWNER="postgres:postgres"; else OWNER="999:999"; fi
fi
if [ "$(id -u)" -eq 0 ]; then
  chown -R "$OWNER" "$PGDATA_DIR"
else
  dr_warn "not running as root: ownership of $PGDATA_DIR was not changed (it must belong to uid 999 for the postgres image)"
fi
chmod 700 "$PGDATA_DIR"

write_report ok
dr_say "data directory ready: $PGDATA_DIR (recovery target: $TARGET). Start PostgreSQL on it; it replays the WAL and opens on a new timeline."
