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
# The backup agent (DR-01): the main process of the dlap-backup sidecar.
#
# It runs next to PostgreSQL and does four things.
#   1. Streams the write-ahead log to disk as it is written (pg_receivewal, a replication slot,
#      synchronous flush), so the local archive trails the database by less than a second.
#   2. Every day takes a logical backup of every database (backup.sh).
#   3. Every week takes a physical base backup (base-backup.sh), the start of a point-in-time restore.
#   4. Every DLAP_WAL_CYCLE_SECONDS copies new WAL, and after each backup the new set, to the place
#      the operator configured (ship.sh), so the copy off the VM stays within the RPO of 15 minutes.
# It also trims old sets and WAL, and keeps small status files that `agent.sh status` reads.
#
# Usage:
#   agent.sh run                      supervise everything; the sidecar's command
#   agent.sh once <job>               run one job now and exit: logical | base | ship | prune
#   agent.sh status [--check] [--quiet]
#                                     print the state of every part; with --check exit 1 when a
#                                     part is late or broken (the container health check)
#
# Environment (defaults in brackets)
#   PGHOST PGPORT PGUSER PGPASSWORD   connection; the role needs SUPERUSER or REPLICATION plus read
#   DLAP_DR_DATA [/backups]           where everything is kept
#   DLAP_BACKUP_AT [02:30]            UTC time of the daily logical backup
#   DLAP_BACKUP_BASE_AT [03:30]       UTC time of the weekly base backup
#   DLAP_BACKUP_BASE_EVERY_DAYS [7]   days between base backups
#   DLAP_BACKUP_KEEP_LOGICAL [14]     logical sets to keep
#   DLAP_BACKUP_KEEP_BASE [3]         base backups to keep (the WAL archive is trimmed to the oldest)
#   DLAP_WAL_CYCLE_SECONDS [300]      how often new WAL is shipped
#   DLAP_WAL_SLOT [dlap_dr]           replication slot of the archive
#   DLAP_WAL_MAX_KEEP_SIZE [4GB]      max_slot_wal_keep_size set on the server when it is unlimited,
#                                     so a stalled archive cannot fill the database disk
#   DLAP_BACKUP_RETRY_SECONDS [1800]  wait before a failed backup is tried again
#   DLAP_DB_LOST_SECONDS [90]         the agent exits after this long without a database, so that Docker
#                                     restarts it into the database container's current network (see run)
#   DLAP_RPO_SECONDS [900]            the target; `status` fails when the off-host copy is older
#   DLAP_BACKUP_MIN_FREE_MB [1024]    `status` fails when the backup volume has less free space;
#   DLAP_BACKUP_WARN_FREE_MB [5120]   it warns below this
#   DLAP_BACKUP_SHIP_CMD, DLAP_BACKUP_PASSPHRASE_FILE, DLAP_ENCRYPTION_KEY_FINGERPRINT   see ship.sh
# Exit: run: 0 after a stop signal, 75 after the database was unreachable for DLAP_DB_LOST_SECONDS;
# once: the job's; status --check: 1 when a part is late or broken.

set -euo pipefail

# shellcheck disable=SC2034  # read by lib.sh
DR_PROG=agent
BIN="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$BIN/lib.sh"

SLOT="${DLAP_WAL_SLOT:-dlap_dr}"
BACKUP_AT="${DLAP_BACKUP_AT:-02:30}"
BASE_AT="${DLAP_BACKUP_BASE_AT:-03:30}"
BASE_EVERY="${DLAP_BACKUP_BASE_EVERY_DAYS:-7}"
CYCLE="${DLAP_WAL_CYCLE_SECONDS:-300}"
RETRY="${DLAP_BACKUP_RETRY_SECONDS:-1800}"
RPO="${DLAP_RPO_SECONDS:-900}"
MAX_KEEP="${DLAP_WAL_MAX_KEEP_SIZE:-4GB}"
DB_LOST="${DLAP_DB_LOST_SECONDS:-90}"
ST="$DR_DATA/status"

usage() { awk '/^# The backup agent/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }

validate_settings() {
  [[ "$BACKUP_AT" =~ ^([01][0-9]|2[0-3]):[0-5][0-9]$ ]] || dr_refuse "DLAP_BACKUP_AT must be HH:MM (UTC), not '$BACKUP_AT'"
  [[ "$BASE_AT" =~ ^([01][0-9]|2[0-3]):[0-5][0-9]$ ]] || dr_refuse "DLAP_BACKUP_BASE_AT must be HH:MM (UTC), not '$BASE_AT'"
  [[ "$BASE_EVERY" =~ ^[0-9]+$ ]] && [ "$BASE_EVERY" -ge 1 ] || dr_refuse "DLAP_BACKUP_BASE_EVERY_DAYS must be a positive whole number"
  [[ "$CYCLE" =~ ^[0-9]+$ ]] && [ "$CYCLE" -ge 30 ] || dr_refuse "DLAP_WAL_CYCLE_SECONDS must be a whole number of at least 30"
  [[ "$RETRY" =~ ^[0-9]+$ ]] || dr_refuse "DLAP_BACKUP_RETRY_SECONDS must be a whole number"
  [[ "$RPO" =~ ^[0-9]+$ ]] || dr_refuse "DLAP_RPO_SECONDS must be a whole number"
  [[ "$MAX_KEEP" =~ ^[0-9]+(kB|MB|GB|TB)$ ]] || dr_refuse "DLAP_WAL_MAX_KEEP_SIZE must look like 4GB (kB, MB, GB or TB), not '$MAX_KEEP'"
  [[ "$SLOT" =~ ^[a-z0-9_]+$ ]] || dr_refuse "DLAP_WAL_SLOT may hold lower case letters, digits and _ only"
  [[ "$DB_LOST" =~ ^[0-9]+$ ]] && [ "$DB_LOST" -ge 30 ] || dr_refuse "DLAP_DB_LOST_SECONDS must be a whole number of at least 30"
}

# --- once ---------------------------------------------------------------------------------------------

cmd_once() {
  [ $# -eq 1 ] || dr_refuse "usage: agent.sh once logical|base|ship|prune"
  case "$1" in
    logical) "$BIN/backup.sh"; "$BIN/ship.sh" --sets || dr_warn "the new set is not off the host yet; the agent tries again every cycle" ;;
    base) "$BIN/base-backup.sh"; "$BIN/ship.sh" --sets || dr_warn "the new set is not off the host yet; the agent tries again every cycle" ;;
    ship) "$BIN/ship.sh" --all ;;
    prune) "$BIN/base-backup.sh" --prune-only ;;
    *) dr_refuse "unknown job '$1' (logical, base, ship or prune)" ;;
  esac
}

# --- status -------------------------------------------------------------------------------------------

cmd_status() {
  local check=0 quiet=0 fails=0 warns=0 now hb age started
  while [ $# -gt 0 ]; do
    case "$1" in
      --check) check=1; shift ;;
      --quiet) quiet=1; shift ;;
      *) dr_refuse "unknown argument: $1" ;;
    esac
  done
  now="$(dr_now)"

  line() { # <OK|WARN|FAIL> <item> <detail>
    case "$1" in FAIL) fails=$((fails + 1)) ;; WARN) warns=$((warns + 1)) ;; esac
    if [ "$quiet" -eq 1 ] && [ "$1" = OK ]; then return 0; fi
    printf '%-5s %-18s %s\n' "$1" "$2" "$3"
  }
  # age_of <file> <key>: seconds since the UTC time stored under <key>, or empty.
  age_of() {
    local t
    t="$(dr_kv "$1" "$2")"
    if [ -z "$t" ]; then return 0; fi
    echo $((now - $(dr_epoch "$t")))
  }

  hb="$(dr_kv "$ST/agent.env" HEARTBEAT_EPOCH)"
  started="$(dr_epoch "$(dr_kv "$ST/agent.env" START_UTC)")"
  if [ -z "$hb" ]; then
    line FAIL agent "has not started (no heartbeat in $ST/agent.env)"
  else
    age=$((now - hb))
    if [ "$age" -gt 120 ]; then line FAIL agent "no heartbeat for ${age}s"; else line OK agent "running since $(dr_kv "$ST/agent.env" START_UTC)"; fi
  fi

  # The database must answer on the loopback interface. If the postgresql container was restarted, this
  # container still holds the old network and cannot reach it: `docker compose up -d` recreates it.
  if pg_isready -q -t 3 -h "${PGHOST:-127.0.0.1}" -p "${PGPORT:-5432}" -d postgres; then
    line OK database "answers on ${PGHOST:-127.0.0.1}:${PGPORT:-5432}"
  else
    line FAIL database "does not answer on ${PGHOST:-127.0.0.1}:${PGPORT:-5432}; if the postgresql container was restarted or recreated, run: docker compose up -d dlap-backup"
  fi

  # Archive: the receiver must be running and reporting.
  local rec rage
  rec="$(dr_kv "$ST/wal.env" RECEIVER)"
  rage="$(age_of "$ST/wal.env" UPDATED_UTC)"
  if [ "$rec" != "running" ]; then
    line FAIL "wal archive" "receiver is ${rec:-not reported}"
  elif [ -n "$rage" ] && [ "$rage" -gt 180 ]; then
    line FAIL "wal archive" "status not updated for ${rage}s"
  else
    line OK "wal archive" "streaming to $(dr_kv "$ST/wal.env" ARCHIVE_KEY), newest $(dr_kv "$ST/wal.env" NEWEST_FILE)"
  fi
  if [ -n "$(dr_kv "$ST/wal.env" CHAIN_BROKEN_UTC)" ]; then
    line WARN "wal archive" "the chain was broken at $(dr_kv "$ST/wal.env" CHAIN_BROKEN_UTC); older base backups cannot reach later moments"
  fi

  # Backups: late is a failure, never having run is pending for the first hour only.
  local kind max last label
  for kind in logical base; do
    if [ "$kind" = logical ]; then max=$(( ${DLAP_BACKUP_MAX_LOGICAL_AGE_HOURS:-26} * 3600 )); else max=$(( ${DLAP_BACKUP_MAX_BASE_AGE_DAYS:-8} * 86400 )); fi
    last="$(age_of "$ST/$kind.env" LAST_SUCCESS_UTC)"
    label="$kind backup"
    if [ -z "$last" ]; then
      if [ $((now - started)) -lt 3600 ] && [ "$started" -gt 0 ]; then
        line WARN "$label" "none yet (the agent started $(( (now - started) / 60 )) minutes ago)"
      else
        line FAIL "$label" "none has succeeded; last error: $(dr_kv "$ST/$kind.env" LAST_ERROR)"
      fi
    elif [ "$last" -gt "$max" ]; then
      line FAIL "$label" "last success $(( last / 3600 )) hours ago (limit $(( max / 3600 ))); last error: $(dr_kv "$ST/$kind.env" LAST_ERROR)"
    else
      line OK "$label" "set $(dr_kv "$ST/$kind.env" LAST_SUCCESS_SET), $(( last / 60 )) minutes ago"
    fi
  done

  # Off-host copy: the RPO is only real when WAL leaves the VM.
  local mode_w mode_s wage pend
  mode_w="$(dr_kv "$ST/ship-wal.env" MODE)"
  mode_s="$(dr_kv "$ST/ship-sets.env" MODE)"
  if [ "$mode_w" = "disabled" ] || { [ -z "$mode_w" ] && [ -z "${DLAP_BACKUP_SHIP_CMD:-}" ]; }; then
    line WARN "off-host copy" "not configured (DLAP_BACKUP_SHIP_CMD is empty): the backups die with this VM"
  elif [ "$mode_w" = "blocked" ]; then
    line FAIL "off-host copy" "blocked: $(dr_kv "$ST/ship-wal.env" LAST_ERROR)"
  else
    wage="$(age_of "$ST/ship-wal.env" WAL_SHIPPED_UPTO_UTC)"
    pend="$(dr_kv "$ST/ship-wal.env" PENDING)"
    if [ -z "$wage" ]; then
      if [ $((now - started)) -lt 1800 ] && [ "$started" -gt 0 ]; then line WARN "off-host WAL" "nothing shipped yet"; else line FAIL "off-host WAL" "nothing has been shipped"; fi
    elif [ "$wage" -gt "$RPO" ]; then
      line FAIL "off-host WAL" "the off-host copy is ${wage}s behind (RPO target ${RPO}s); pending ${pend:-?}; $(dr_kv "$ST/ship-wal.env" LAST_ERROR)"
    else
      line OK "off-host WAL" "the off-host copy is ${wage}s behind (RPO target ${RPO}s)"
    fi
    local sage
    sage="$(age_of "$ST/ship-sets.env" LAST_OK_UTC)"
    if [ -n "$(dr_kv "$ST/ship-sets.env" LAST_ERROR)" ]; then
      line FAIL "off-host sets" "$(dr_kv "$ST/ship-sets.env" LAST_ERROR)"
    elif [ -n "$sage" ] && [ "$sage" -gt $((CYCLE * 4 + 600)) ]; then
      line FAIL "off-host sets" "no complete pass for ${sage}s; the shipper may not be running"
    elif [ "$mode_s" = "enabled" ]; then
      line OK "off-host sets" "all backup sets shipped (last pass $(dr_kv "$ST/ship-sets.env" LAST_PASS_UTC))"
    fi
  fi

  # The backup volume: a full disk stops every backup, and the WAL archive is the first thing to fill it.
  local free min_mb warn_mb
  free="$(dr_free_bytes "$DR_DATA" 2> /dev/null || echo 0)"
  min_mb="${DLAP_BACKUP_MIN_FREE_MB:-1024}"
  warn_mb="${DLAP_BACKUP_WARN_FREE_MB:-5120}"
  if [ "$free" -lt $((min_mb * 1048576)) ]; then
    line FAIL "backup space" "only $(dr_mib "$free") MiB free in $DR_DATA (limit $min_mb MiB)"
  elif [ "$free" -lt $((warn_mb * 1048576)) ]; then
    line WARN "backup space" "$(dr_mib "$free") MiB free in $DR_DATA (warning below $warn_mb MiB)"
  else
    line OK "backup space" "$(dr_mib "$free") MiB free in $DR_DATA"
  fi

  # The key fingerprint is what lets a restore refuse a wrong DLAP_ENCRYPTION_KEY.
  if [ -z "${DLAP_ENCRYPTION_KEY_FINGERPRINT:-}" ]; then
    line WARN "key escrow" "DLAP_ENCRYPTION_KEY_FINGERPRINT is not set; backups cannot record which key their credentials need"
  fi

  if [ "$quiet" -eq 0 ]; then printf 'RPO target %ss, RTO target %ss\n' "$RPO" "${DLAP_RTO_SECONDS:-14400}"; fi
  if [ "$check" -eq 1 ] && [ "$fails" -gt 0 ]; then return 1; fi
  return 0
}

# --- run ----------------------------------------------------------------------------------------------

STOP=0
RECEIVER_PID=""
ARCHIVE_KEY=""
RECEIVER_STARTS=0
declare -A JOB_PID=()

# slot_state: missing | the slot's wal_status (reserved, extended, unreserved, lost).
slot_state() {
  dr_psql -d postgres -v slot="$SLOT" <<'SQL'
SELECT coalesce((SELECT coalesce(wal_status, 'reserved') FROM pg_replication_slots WHERE slot_name = :'slot'), 'missing');
SQL
}

# ensure_slot: create the slot when it is missing; replace it when the server gave up on it. A
# missing or invalidated slot means WAL may have been lost since the archive last saw it, so the
# archive starts a new generation (a new directory) and a new base backup is asked for.
ensure_slot() {
  local state key
  state="$(slot_state)"
  key="$(dr_archive_key)"
  case "$state" in
    missing)
      dr_say "creating replication slot $SLOT"
      pg_receivewal --create-slot --if-not-exists --slot="$SLOT" --no-password -h "${PGHOST:-127.0.0.1}" -p "${PGPORT:-5432}" -U "${PGUSER:-dlap}"
      if [ -n "$(find "$DR_DATA/wal/$key" -maxdepth 1 -type f 2> /dev/null | head -n 1)" ]; then
        dr_err "the slot $SLOT did not exist although the archive holds WAL: segments may be missing between the archive and the new slot. Starting a new archive generation and asking for a new base backup."
        chain_broken
      fi
      ;;
    lost)
      dr_err "replication slot $SLOT was invalidated: the archive fell more than $MAX_KEEP behind. Older base backups cannot reach moments after the gap. Replacing the slot, starting a new archive generation and asking for a new base backup."
      pg_receivewal --drop-slot --slot="$SLOT" --no-password -h "${PGHOST:-127.0.0.1}" -p "${PGPORT:-5432}" -U "${PGUSER:-dlap}"
      pg_receivewal --create-slot --slot="$SLOT" --no-password -h "${PGHOST:-127.0.0.1}" -p "${PGPORT:-5432}" -U "${PGUSER:-dlap}"
      chain_broken
      ;;
  esac
}

# chain_broken: record the break, move to the next archive generation, ask for a base backup.
chain_broken() {
  local gen
  gen="$(dr_kv "$ST/archive-generation" GENERATION)"
  gen=$(( ${gen:-0} + 1 ))
  dr_kv_set "$ST/archive-generation" "GENERATION=$gen"
  dr_kv_set "$ST/wal.env" "CHAIN_BROKEN_UTC=$(dr_ts)"
  : > "$ST/need-base"
}

# tune_server: bound the WAL a stalled archive can pin. The parameter is reloadable, and
# ALTER SYSTEM persists it in postgresql.auto.conf of the data directory.
tune_server() {
  local current
  current="$(dr_psql -d postgres -c 'SHOW max_slot_wal_keep_size')"
  if [ "$current" = "-1" ]; then
    dr_psql -d postgres -c "ALTER SYSTEM SET max_slot_wal_keep_size = '$MAX_KEEP'"
    dr_psql -d postgres -c 'SELECT pg_reload_conf()' > /dev/null
    dr_say "max_slot_wal_keep_size set to $MAX_KEEP (postgresql.auto.conf): a stalled archive cannot fill the data disk"
  fi
}

start_receiver() {
  local dir
  ensure_slot
  ARCHIVE_KEY="$(dr_archive_key)"
  dir="$DR_DATA/wal/$ARCHIVE_KEY"
  mkdir -p "$dir"
  pg_receivewal -D "$dir" -h "${PGHOST:-127.0.0.1}" -p "${PGPORT:-5432}" -U "${PGUSER:-dlap}" \
    --slot="$SLOT" --synchronous --no-password &
  RECEIVER_PID=$!
  RECEIVER_STARTS=$((RECEIVER_STARTS + 1))
  dr_say "streaming WAL into wal/$ARCHIVE_KEY (pid $RECEIVER_PID, slot $SLOT)"
}

receiver_alive() { [ -n "$RECEIVER_PID" ] && kill -0 "$RECEIVER_PID" 2>/dev/null; }

write_wal_status() {
  local state newest="" dir="$DR_DATA/wal/$ARCHIVE_KEY"
  if receiver_alive; then state=running; else state=stopped; fi
  if [ -d "$dir" ]; then newest="$(find "$dir" -maxdepth 1 -type f -printf '%f\n' | LC_ALL=C sort | tail -n 1)"; fi
  dr_kv_set "$ST/wal.env" "RECEIVER=$state" "ARCHIVE_KEY=$ARCHIVE_KEY" "SLOT=$SLOT" "NEWEST_FILE=$newest" \
    "UPDATED_UTC=$(dr_ts)" "UPDATED_EPOCH=$(dr_now)" "RECEIVER_STARTS=$RECEIVER_STARTS"
}

start_job() { # <name> <command...>
  local name="$1"
  shift
  if [ -n "${JOB_PID[$name]:-}" ]; then return 0; fi
  "$@" &
  JOB_PID[$name]=$!
  dr_say "job $name started (pid ${JOB_PID[$name]})"
}

# reap_jobs: collect finished jobs; a finished backup starts the shipping of its set.
reap_jobs() {
  local name pid rc
  for name in "${!JOB_PID[@]}"; do
    pid="${JOB_PID[$name]}"
    if ! kill -0 "$pid" 2>/dev/null; then
      rc=0
      wait "$pid" || rc=$?
      unset 'JOB_PID[$name]'
      if [ "$rc" -eq 0 ]; then dr_say "job $name finished"; else dr_err "job $name failed (exit $rc)"; fi
      case "$name" in
        logical|base) if [ "$rc" -eq 0 ]; then start_job ship-sets "$BIN/ship.sh" --sets; fi ;;
      esac
    fi
  done
}

last_epoch() { dr_epoch "$(dr_kv "$ST/$1.env" "$2")"; }

schedule() {
  local now="$1" last attempt
  # Logical backup.
  last="$(last_epoch logical LAST_SUCCESS_UTC)"
  attempt="$(last_epoch logical LAST_ATTEMPT_UTC)"
  if [ -z "${JOB_PID[logical]:-}" ] && dr_due_daily "$BACKUP_AT" "$last" "$now" && [ $((now - attempt)) -ge "$RETRY" ]; then
    start_job logical "$BIN/backup.sh"
  fi
  # Base backup: only while the archive streams, or the set would have nothing after it. A broken
  # chain asks for one at once.
  last="$(last_epoch base LAST_SUCCESS_UTC)"
  attempt="$(last_epoch base LAST_ATTEMPT_UTC)"
  if [ -z "${JOB_PID[base]:-}" ] && receiver_alive && [ $((now - attempt)) -ge "$RETRY" ]; then
    if [ -f "$ST/need-base" ] || dr_due_every "$BASE_EVERY" "$BASE_AT" "$last" "$now"; then
      rm -f "$ST/need-base"
      start_job base "$BIN/base-backup.sh"
    fi
  fi
}

cmd_run() {
  validate_settings
  mkdir -p "$DR_DATA"/{logical,base,wal,ship,status,tmp}
  trap 'STOP=1' TERM INT
  dr_kv_set "$ST/agent.env" "START_UTC=$(dr_ts)" "HEARTBEAT_UTC=$(dr_ts)" "HEARTBEAT_EPOCH=$(dr_now)"
  dr_say "starting; data in $DR_DATA, daily backup at $BACKUP_AT UTC, base backup every $BASE_EVERY days after $BASE_AT UTC, WAL shipped every ${CYCLE}s"
  dr_ship_enabled || dr_warn "DLAP_BACKUP_SHIP_CMD is not set: nothing leaves this VM, so the backups die with it"
  dr_have_passphrase || dr_warn "no passphrase file at $DR_PASSFILE: copies cannot be encrypted before they are shipped"

  until dr_wait_pg 30; do
    [ "$STOP" -eq 0 ] || return 0
    dr_warn "waiting for PostgreSQL"
  done
  tune_server
  start_receiver

  local next_cycle=0 next_wal_status=0 now backoff_until=0 fast_exits=0 started_at db_down_since=0 exit_code=0
  while [ "$STOP" -eq 0 ]; do
    now="$(dr_now)"
    dr_kv_set "$ST/agent.env" "HEARTBEAT_UTC=$(dr_ts)" "HEARTBEAT_EPOCH=$now"
    reap_jobs

    # No database for DLAP_DB_LOST_SECONDS: either it is down, or the postgresql container was restarted
    # and this container, which shares its network, still holds the old one, where nothing listens any
    # more. Exiting makes Docker restart us into the current network; on start we wait for the database.
    if pg_isready -q -t 3 -h "${PGHOST:-127.0.0.1}" -p "${PGPORT:-5432}" -d postgres; then
      db_down_since=0
    else
      if [ "$db_down_since" -eq 0 ]; then db_down_since="$now"; fi
      if [ $((now - db_down_since)) -ge "$DB_LOST" ]; then
        dr_err "the database has not answered for $((now - db_down_since)) s; exiting so that Docker restarts this container into the current network of the postgresql container"
        exit_code=75
        break
      fi
    fi

    if ! receiver_alive; then
      if [ "$now" -ge "$backoff_until" ]; then
        wait "$RECEIVER_PID" 2>/dev/null || true
        dr_warn "the WAL receiver is not running; restarting it"
        started_at="$now"
        if dr_wait_pg 10 && start_receiver; then
          fast_exits=$((fast_exits + 1))
          backoff_until=$((started_at + (fast_exits < 6 ? 5 * fast_exits : 30)))
        else
          backoff_until=$((now + 30))
        fi
      fi
    fi

    if [ "$now" -ge "$next_wal_status" ]; then
      write_wal_status
      next_wal_status=$((now + 20))
      # A receiver that has stayed up for a minute is healthy again.
      if receiver_alive && [ "$fast_exits" -gt 0 ] && [ "$now" -gt "$backoff_until" ]; then fast_exits=0; fi
    fi
    if [ "$now" -ge "$next_cycle" ]; then
      start_job ship-wal "$BIN/ship.sh" --wal
      # Backup sets that failed to upload are tried again every cycle, not only after the next backup.
      start_job ship-sets "$BIN/ship.sh" --sets
      next_cycle=$((now + CYCLE))
    fi
    schedule "$now"

    sleep 5 &
    wait $! || true
  done

  [ "$exit_code" -ne 0 ] || dr_say "stop requested"
  if receiver_alive; then kill -TERM "$RECEIVER_PID" 2>/dev/null || true; wait "$RECEIVER_PID" 2>/dev/null || true; fi
  local name
  for name in "${!JOB_PID[@]}"; do kill -TERM "${JOB_PID[$name]}" 2>/dev/null || true; done
  wait 2>/dev/null || true
  dr_kv_set "$ST/wal.env" "RECEIVER=stopped" "UPDATED_UTC=$(dr_ts)" || true
  dr_say "stopped"
  return "$exit_code"
}

# --- main ---------------------------------------------------------------------------------------------

[ $# -ge 1 ] || { usage; exit 2; }
CMD="$1"
shift
case "$CMD" in
  run) cmd_run "$@" ;;
  once) validate_settings; cmd_once "$@" ;;
  status) cmd_status "$@" ;;
  -h|--help) usage ;;
  *) dr_refuse "unknown command '$CMD' (run, once, status)" ;;
esac
