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
# Scheduled backup of the Keycloak realm of the quickstart stack, run from the host (DR-03, Q-751).
#
# The quickstart runs Keycloak as `start-dev` on an embedded database inside the container, so the
# PostgreSQL backups do not carry its realm (users with their password hashes, roles, clients, secrets).
# keycloak-realm.sh exports it, but it needs Docker (it stops the container, snapshots it, exports from the
# snapshot) and the backup agent has no Docker access, so nothing in the stack runs it. This wrapper is
# what cron or a systemd timer on the HOST runs. The guide (docs/guides/backup-and-restore.md, section 8.7)
# has the crontab line and the timer unit. Keycloak on PostgreSQL (the pilot overlay) needs none of this.
#
# One run, in order
#   1. takes a lock on the state folder, so two runs never overlap (a second one exits 2)
#   2. keycloak-realm.sh backup into a Docker volume on the host (the staging volume), key
#      keycloak/<UTC stamp>/: the realm file encrypted with the backup passphrase, manifest.env and,
#      last, SHA256SUMS.shipped. The plain realm never reaches the host disk. Keycloak is down for the
#      length of a container copy (a few seconds to half a minute): schedule it for a quiet hour.
#   3. ships every set that has not been shipped yet, oldest first, through DLAP_BACKUP_SHIP_CMD with
#      the same contract as ship.sh: sh -c "$DLAP_BACKUP_SHIP_CMD" ship <file> <key>, run on the host,
#      with SHA256SUMS.shipped last. Only encrypted files and the two small text files are handed over.
#      A set whose shipping failed is tried again on the next run.
#   4. removes shipped sets from the staging volume beyond the newest DLAP_KC_KEEP (never an unshipped one)
#   5. writes the status file that `check` reads
#
# Usage:
#   keycloak-realm-backup.sh [run] [--env-file FILE]     one backup and ship pass (the default command)
#   keycloak-realm-backup.sh check [--quiet] [--env-file FILE]
#                                                        exit 1 unless the last success is recent enough;
#                                                        --quiet prints nothing when it is (for cron mail)
#   keycloak-realm-backup.sh status [--env-file FILE]    print the status file
#
# Settings, from the environment or from --env-file (KEY=value lines; the file is parsed, never executed;
# a value may be wrapped in one pair of single or double quotes, as in .env.pilot)
#   DLAP_BACKUP_PASSPHRASE_FILE   required: the file with the one-line passphrase (as for the backup agent)
#   DLAP_BACKUP_SHIP_CMD          the off-host command (guide section 5.1). Without it the sets stay on this
#                                 host and every run warns: a copy on the same VM does not survive the VM.
#   DLAP_BACKUP_SHIP_TIMEOUT      seconds one file may take [1800]
#   DLAP_KC_CONTAINER             the Keycloak container [dlap-keycloak]
#   DLAP_KC_REALM                 the realm [dlap]
#   DLAP_KC_BACKUP_VOLUME         the staging volume [dlap-keycloak-realm-backups]
#   DLAP_KC_KEEP                  shipped sets kept in the staging volume [14]
#   DLAP_KC_MAX_AGE_HOURS         age at which `check` fails [26]
#   DLAP_KC_STATE_DIR             lock and status files [/var/lib/dlap-keycloak-backup as root, else
#                                 $HOME/.local/state/dlap-keycloak-backup]
#   DLAP_KC_TOOL                  the export tool [keycloak-realm.sh next to this file]; a test seam
#   DLAP_DR_IMAGE                 image with bash and tar for the volume helpers [postgres:15.1]
# Needs: docker, tar, sh on the host. Exit: 0 done; 1 a step failed; 2 a refusal, a bad argument or a run
# already in progress (nothing was changed).

set -euo pipefail
export MSYS_NO_PATHCONV=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
say() { printf '%s keycloak-realm-backup: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
warn() { printf '%s keycloak-realm-backup: WARN: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
refuse() { printf 'keycloak-realm-backup: ERROR: %s\n' "$*" >&2; exit 2; }
die() { printf 'keycloak-realm-backup: ERROR: %s\n' "$*" >&2; exit 1; }
usage() { awk '/^# Scheduled backup of the Keycloak realm/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }
hostpath() { if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

CMD="run"
case "${1:-}" in
  run | check | status) CMD="$1"; shift ;;
  -h | --help) usage; exit 0 ;;
esac

ENV_FILE=""
QUIET=0
while [ $# -gt 0 ]; do
  case "$1" in
    -q | --quiet) QUIET=1; shift ;;
    --env-file) [ $# -ge 2 ] || refuse "--env-file needs a file"; ENV_FILE="$2"; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) refuse "unknown argument: $1" ;;
  esac
done

# load_env <file>: copy the DLAP_* settings of the file into the environment unless they are already set there.
# The file is read line by line and never run, so a stray $(...) in it does nothing.
load_env() {
  local line key val
  [ -f "$1" ] || refuse "the environment file $1 does not exist"
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*(DLAP_[A-Z0-9_]+)=(.*)$ ]] || continue
    key="${BASH_REMATCH[1]}"
    val="${BASH_REMATCH[2]}"
    if [[ "$val" =~ ^\'(.*)\'$ ]]; then val="${BASH_REMATCH[1]}"; elif [[ "$val" =~ ^\"(.*)\"$ ]]; then val="${BASH_REMATCH[1]}"; fi
    if [ -z "${!key:-}" ]; then export "$key=$val"; fi
  done < "$1"
}
if [ -n "$ENV_FILE" ]; then load_env "$ENV_FILE"; fi

CONTAINER="${DLAP_KC_CONTAINER:-dlap-keycloak}"
REALM="${DLAP_KC_REALM:-dlap}"
VOLUME="${DLAP_KC_BACKUP_VOLUME:-dlap-keycloak-realm-backups}"
KEEP="${DLAP_KC_KEEP:-14}"
MAX_AGE_H="${DLAP_KC_MAX_AGE_HOURS:-26}"
SHIP_CMD="${DLAP_BACKUP_SHIP_CMD:-}"
SHIP_TIMEOUT="${DLAP_BACKUP_SHIP_TIMEOUT:-1800}"
TOOL="${DLAP_KC_TOOL:-$SCRIPT_DIR/keycloak-realm.sh}"
IMAGE="${DLAP_DR_IMAGE:-postgres:15.1}"
if [ -n "${DLAP_KC_STATE_DIR:-}" ]; then
  STATE_DIR="$DLAP_KC_STATE_DIR"
elif [ "$(id -u)" -eq 0 ]; then
  STATE_DIR="/var/lib/dlap-keycloak-backup"
else
  STATE_DIR="${HOME:-/tmp}/.local/state/dlap-keycloak-backup"
fi
STATUS_FILE="$STATE_DIR/status.env"

NAME_RE='^[A-Za-z0-9][A-Za-z0-9_.-]*$'
[[ "$CONTAINER" =~ $NAME_RE ]] || refuse "not a container name: $CONTAINER"
[[ "$REALM" =~ $NAME_RE ]] || refuse "not a realm name: $REALM"
[[ "$VOLUME" =~ $NAME_RE ]] || refuse "not a volume name: $VOLUME"
[[ "$KEEP" =~ ^[0-9]+$ ]] && [ "$KEEP" -ge 1 ] || refuse "DLAP_KC_KEEP must be a positive whole number, not '$KEEP'"
[[ "$MAX_AGE_H" =~ ^[0-9]+$ ]] && [ "$MAX_AGE_H" -ge 1 ] || refuse "DLAP_KC_MAX_AGE_HOURS must be a positive whole number, not '$MAX_AGE_H'"
[[ "$SHIP_TIMEOUT" =~ ^[0-9]+$ ]] && [ "$SHIP_TIMEOUT" -ge 1 ] || refuse "DLAP_BACKUP_SHIP_TIMEOUT must be a positive whole number, not '$SHIP_TIMEOUT'"

# kv <file> <key>: the value of KEY=value, empty when absent.
kv() { [ -f "$1" ] && sed -n "s/^$2=//p" "$1" | tail -n 1 | tr -d '\r' || true; }
# kv_set <file> KEY=value...: replace or add the keys, atomically.
kv_set() {
  local f="$1" tmp kvp key
  shift
  mkdir -p "$(dirname "$f")"
  tmp="$f.tmp.$$"
  if [ -f "$f" ]; then cp "$f" "$tmp"; else : > "$tmp"; fi
  for kvp in "$@"; do
    key="${kvp%%=*}"
    grep -v "^$key=" "$tmp" > "$tmp.2" || true
    printf '%s\n' "$kvp" >> "$tmp.2"
    mv "$tmp.2" "$tmp"
  done
  mv "$tmp" "$f"
}
now() { date -u +%s; }
ts() { date -u +%Y-%m-%dT%H:%M:%SZ; }
epoch() { if [ -n "${1:-}" ]; then date -u -d "$1" +%s 2> /dev/null || echo 0; else echo 0; fi; }

# --- status and check -------------------------------------------------------------------------------------

if [ "$CMD" = status ]; then
  [ -f "$STATUS_FILE" ] || { echo "no status yet: $STATUS_FILE does not exist"; exit 1; }
  cat "$STATUS_FILE"
  exit 0
fi

if [ "$CMD" = check ]; then
  last="$(kv "$STATUS_FILE" LAST_SUCCESS_UTC)"
  [ -n "$last" ] || { echo "FAIL  no successful realm backup is recorded in $STATUS_FILE"; exit 1; }
  age=$(( $(now) - $(epoch "$last") ))
  if [ "$(epoch "$last")" -eq 0 ] || [ "$age" -gt $((MAX_AGE_H * 3600)) ]; then
    echo "FAIL  the last successful realm backup is from $last, older than ${MAX_AGE_H} hours"
    exit 1
  fi
  if [ "$(kv "$STATUS_FILE" LAST_RESULT)" != ok ]; then
    echo "FAIL  the last run did not succeed ($(kv "$STATUS_FILE" LAST_RESULT)): $(kv "$STATUS_FILE" LAST_ERROR)"
    exit 1
  fi
  if [ "$QUIET" -eq 0 ]; then echo "OK    the last successful realm backup is from $last ($((age / 60)) minutes ago), set $(kv "$STATUS_FILE" LAST_SET)"; fi
  exit 0
fi

# --- run ---------------------------------------------------------------------------------------------------

command -v docker > /dev/null 2>&1 || refuse "docker is not installed (this script runs on the host)"
docker info > /dev/null 2>&1 || refuse "the Docker daemon does not answer"
[ -f "$TOOL" ] || refuse "the export tool $TOOL does not exist"
PASSFILE="${DLAP_BACKUP_PASSPHRASE_FILE:-}"
[ -n "$PASSFILE" ] || refuse "DLAP_BACKUP_PASSPHRASE_FILE is not set (the realm holds password hashes and client secrets: it is never stored in plain)"
[ -s "$PASSFILE" ] || refuse "the passphrase file is missing or empty: $PASSFILE"
docker inspect "$CONTAINER" > /dev/null 2>&1 || refuse "no container named $CONTAINER; set DLAP_KC_CONTAINER"

umask 077
mkdir -p "$STATE_DIR/shipped"
# A directory is the lock: mkdir is atomic everywhere (flock is not on every host). A lock whose process is gone is stale.
LOCK="$STATE_DIR/lock.d"
if ! mkdir "$LOCK" 2> /dev/null; then
  other="$(cat "$LOCK/pid" 2> /dev/null || true)"
  if [ -n "$other" ] && kill -0 "$other" 2> /dev/null; then refuse "another run is in progress (process $other)"; fi
  warn "removing the stale lock of process ${other:-unknown}"
  rm -rf "$LOCK"
  mkdir "$LOCK" 2> /dev/null || refuse "another run took the lock"
fi
echo "$$" > "$LOCK/pid"

STAGE=""
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
T0="$(now)"
STEP="start"
RESULT="failed"
SHIPPED_NOW=0
on_exit() {
  local rc=$?
  [ -z "$STAGE" ] || rm -rf "$STAGE"
  rm -rf "$LOCK"
  if [ "$RESULT" = failed ] && [ "$rc" -ne 0 ]; then
    kv_set "$STATUS_FILE" "LAST_ATTEMPT_UTC=$(ts)" "LAST_RESULT=failed" "LAST_ERROR=failed in step '$STEP' (exit $rc)" || true
    printf 'keycloak-realm-backup: ERROR: the run failed in step %s (exit %s); see %s\n' "$STEP" "$rc" "$STATUS_FILE" >&2
  fi
}
trap on_exit EXIT

vol() { # <ro|rw> <script>: a shell snippet in a throwaway container with the staging volume at /v
  local mode="$1" opt=""
  [ "$mode" = ro ] && opt=":ro"
  docker run --rm -i -v "$VOLUME:/v$opt" --entrypoint bash "$IMAGE" -c "$2"
}

STEP="volume"
docker volume inspect "$VOLUME" > /dev/null 2>&1 || docker volume create "$VOLUME" > /dev/null || die "cannot create the volume $VOLUME"

STEP="backup"
KEY="keycloak/$STAMP"
say "exporting realm $REALM of $CONTAINER into volume $VOLUME as $KEY"
DLAP_BACKUP_PASSPHRASE_FILE="$PASSFILE" bash "$TOOL" backup --container "$CONTAINER" --realm "$REALM" --to-volume "$VOLUME" --key "$KEY" \
  || die "keycloak-realm.sh backup failed"
vol ro "test -f \"/v/$KEY/SHA256SUMS.shipped\"" || die "the backup left no SHA256SUMS.shipped under $KEY"

# --- ship every set not shipped yet, oldest first ----------------------------------------------------------

# ship_set <stamp>: hand the files of keycloak/<stamp> to the off-host command, the marker last.
ship_set() {
  local s="$1" f
  STAGE="$(mktemp -d "${TMPDIR:-/tmp}/dlap-kc-ship.XXXXXX")"
  docker run --rm -v "$VOLUME:/v:ro" --entrypoint tar "$IMAGE" -C /v -cf - "keycloak/$s" | tar -xf - -C "$STAGE" || { rm -rf "$STAGE"; STAGE=""; return 1; }
  for f in "$REALM-realm.json.enc" manifest.env SHA256SUMS.shipped; do
    [ -f "$STAGE/keycloak/$s/$f" ] || { warn "keycloak/$s/$f is missing in the staging volume"; rm -rf "$STAGE"; STAGE=""; return 1; }
    if ! timeout "$SHIP_TIMEOUT" sh -c "$SHIP_CMD" ship "$STAGE/keycloak/$s/$f" "keycloak/$s/$f"; then
      warn "the off-host command failed for keycloak/$s/$f"
      rm -rf "$STAGE"; STAGE=""
      return 1
    fi
  done
  rm -rf "$STAGE"
  STAGE=""
  : > "$STATE_DIR/shipped/$s"
  SHIPPED_NOW=$((SHIPPED_NOW + 1))
  say "shipped keycloak/$s"
}

STEP="ship"
SHIP_FAILED=0
mapfile -t SETS < <(vol ro 'ls -1 /v/keycloak 2> /dev/null | LC_ALL=C sort' | tr -d '\r')
if [ -z "$SHIP_CMD" ]; then
  warn "DLAP_BACKUP_SHIP_CMD is not set: the realm backup stays on this host and does not survive the loss of the VM"
else
  for s in "${SETS[@]}"; do
    [[ "$s" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || continue
    [ -f "$STATE_DIR/shipped/$s" ] && continue
    vol ro "test -f \"/v/keycloak/$s/SHA256SUMS.shipped\"" || { warn "keycloak/$s is incomplete (no SHA256SUMS.shipped) and is not shipped"; continue; }
    ship_set "$s" || SHIP_FAILED=1
  done
fi

# --- trim the staging volume ------------------------------------------------------------------------------

STEP="trim"
if [ -n "$SHIP_CMD" ]; then
  # Only shipped sets count and only they go; an unshipped set is the only copy there is.
  shipped_sets=()
  for s in "${SETS[@]}"; do [ -f "$STATE_DIR/shipped/$s" ] && shipped_sets+=("$s"); done
  excess=$(( ${#shipped_sets[@]} - KEEP ))
  i=0
  while [ "$i" -lt "$excess" ]; do
    s="${shipped_sets[$i]}"
    vol rw "rm -rf \"/v/keycloak/$s\"" && rm -f "$STATE_DIR/shipped/$s"
    say "removed the shipped set keycloak/$s from $VOLUME (keeping $KEEP)"
    i=$((i + 1))
  done
else
  # No off-host command: the staging volume is the only copy, so keep the newest KEEP and warn above.
  excess=$(( ${#SETS[@]} - KEEP ))
  i=0
  while [ "$i" -lt "$excess" ]; do
    s="${SETS[$i]}"
    [[ "$s" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] && vol rw "rm -rf \"/v/keycloak/$s\""
    i=$((i + 1))
  done
fi

# --- status ------------------------------------------------------------------------------------------------

STEP="status"
if [ "$SHIP_FAILED" -eq 1 ]; then
  kv_set "$STATUS_FILE" "LAST_ATTEMPT_UTC=$(ts)" "LAST_RESULT=ship-failed" "LAST_ERROR=the off-host command failed; the set is in $VOLUME and is shipped again by the next run" \
    "LAST_SET=$STAMP" "LAST_BACKUP_UTC=$(ts)"
  RESULT="ship-failed"
  die "the backup keycloak/$STAMP is in volume $VOLUME, but not every set could be shipped; the next run tries again"
fi
if [ -n "$SHIP_CMD" ]; then
  kv_set "$STATUS_FILE" "LAST_ATTEMPT_UTC=$(ts)" "LAST_SUCCESS_UTC=$(ts)" "LAST_RESULT=ok" "LAST_ERROR=" "LAST_SET=$STAMP" \
    "LAST_SECONDS=$(( $(now) - T0 ))" "SHIPPED=yes" "SHIPPED_THIS_RUN=$SHIPPED_NOW"
else
  kv_set "$STATUS_FILE" "LAST_ATTEMPT_UTC=$(ts)" "LAST_SUCCESS_UTC=$(ts)" "LAST_RESULT=ok" "LAST_ERROR=" "LAST_SET=$STAMP" \
    "LAST_SECONDS=$(( $(now) - T0 ))" "SHIPPED=no" "SHIPPED_THIS_RUN=0"
fi
RESULT="ok"
say "done in $(( $(now) - T0 )) s: set keycloak/$STAMP, $SHIPPED_NOW set(s) shipped"
