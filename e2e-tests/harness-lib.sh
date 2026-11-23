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
#
# ---------------------------------------------------------------------------
# Shared helpers for the e2e harnesses (HARN-01). SOURCE this file, do not run it:
#
#   source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/harness-lib.sh"
#   harness_init "<label>" "$REPO"      # lock, project name, ports, JDK
#   ...
#   harness_reset_stack                 # clean slate before `compose up`
#
# Why it exists. Every harness drives ONE shared Docker host: fixed container names
# (dlap-*), fixed host ports and `down -v` at the start of each run. Two runs at once
# (two agents, two worktrees, a dev stack) delete each other's stacks and produce false
# greens and false reds. This file gives each run:
#
#   1. A host-wide lock around the whole run. flock(1) on a file under one fixed per-user
#      directory (harness_lock_base, never TMPDIR) when flock exists (Linux, CI); otherwise a
#      mkdir lock with PID and age based stale detection (Git Bash on Windows, macOS). The lock
#      dies with the run: flock releases on process death, the mkdir lock is reclaimed when its
#      holder PID is gone.
#   2. COMPOSE_PROJECT_NAME derived per worktree (dlap-e2e-<dir>-<hash of the path>), so one
#      worktree's `down -v` never touches another worktree's volumes or the dev stack's.
#   3. Host ports from the environment: DLAP_PORT_<SERVICE>, defaults equal to the values the
#      dev compose pair has always published (see HARNESS_PORT_DEFAULTS).
#   4. harness_wait_log: a log wait that survives `set -o pipefail` (no SIGPIPE false wait).
#   5. harness_evict_colliding: because the compose files pin container_name, a stack left up
#      by another worktree must be evicted before this run can start. Harness projects get a
#      full `down -v`; anything else (for example the hand-run dev stack) only loses the
#      colliding containers, never its volumes.
#
# Environment knobs (all optional):
#   DLAP_HARNESS_LOCK_DIR      directory of the lock. Leave it unset: the default is one fixed
#                              absolute path per user, <home>/.dlap/harness-lock, the same for every
#                              shell whatever its TMPDIR (Q-577). Two callers with different values
#                              do not exclude each other; the self-test sets it to stay private.
#   DLAP_HARNESS_LOCK_HOME     home directory the default is built from (default: the Windows
#                              USERPROFILE under Git Bash, else the passwd home, else $HOME); a
#                              test seam, not a setting
#   DLAP_HARNESS_LOCK_TIMEOUT  seconds to wait for the lock (default 3600)
#   DLAP_HARNESS_LOCK_MAX_AGE  seconds after which a mkdir lock is stale even if its PID
#                              looks alive, for PID reuse after a reboot (default 14400)
#   DLAP_HARNESS_LOCK_BACKEND  force flock or mkdir (default: flock when installed)
#   DLAP_HARNESS_LOCK_HELD     set by the lock holder for its children; a child that finds a
#                              live ancestor PID here does not take the lock again
#   COMPOSE_PROJECT_NAME       wins over the derived project name
#   DLAP_PORT_<SERVICE>        host port overrides, see HARNESS_PORT_DEFAULTS
#   DLAP_JDK21                 JDK 21 home for the Gradle jar build
#
# Caller contract: harness_lock_acquire installs EXIT, INT and TERM traps. A script that
# sets its own EXIT trap afterwards must call harness_lock_release from it. File
# descriptor 9 belongs to the flock backend; start long-lived children (Gradle) with 9>&-
# so a lingering daemon cannot hold the lock after the run ends.
# ---------------------------------------------------------------------------

_hl_say()  { echo "[e2e] $*"; }
_hl_warn() { echo "[e2e] WARN: $*" >&2; }
_hl_die()  { echo "[e2e] FAIL: $*" >&2; exit 1; }

_HL_LOCK_BACKEND=""   # flock | mkdir while this shell holds the lock
_HL_LOCK_PATH=""      # lock file (flock) or lock directory (mkdir)

# ---------------------------------------------------------------------------
# Host-wide lock
# ---------------------------------------------------------------------------

# True when <pid> names a live process. kill -0 first; /proc covers EPERM on Linux and the
# MSYS process table on Windows.
_hl_pid_alive() {
  [[ "${1:-}" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$1" 2>/dev/null || [[ -e "/proc/$1" ]]
}

# True when a lock written as "<pid> <epoch> ..." is stale: dead PID, or older than max_age.
_hl_holder_stale() { # _hl_holder_stale <pid> <epoch> <max_age>
  local hp="$1" hs="$2" max_age="$3" now
  _hl_pid_alive "$hp" || return 0
  if [[ "$hs" =~ ^[0-9]+$ ]]; then
    now="$(date +%s)"
    (( now - hs > max_age )) && return 0
  fi
  return 1
}

# Human text for a holder record "<pid> <epoch> <label> <cwd>".
_hl_holder_text() { # _hl_holder_text <record file>
  local hp="" hs="" lab="" cwd=""
  read -r hp hs lab cwd 2>/dev/null < "$1" || { echo "unknown"; return 0; }
  [[ "$hs" =~ ^[0-9]+$ ]] || { echo "pid ${hp:-?}"; return 0; }
  echo "pid $hp, $lab, $cwd, running $(( $(date +%s) - hs ))s"
}

# Remove a stale mkdir lock. Serialised by a second mkdir guard so two waiters cannot both
# decide "stale" and one of them delete a lock the other has just taken. The decision is
# re-checked under the guard.
_hl_reclaim() { # _hl_reclaim <lock dir> <holder pid seen, or empty>
  local d="$1" seen="$2" g="$1.reclaim" cur="" cs=""
  if ! mkdir "$g" 2>/dev/null; then
    # Someone else is reclaiming. A guard older than a minute belongs to a crashed reclaimer.
    find "$g" -maxdepth 0 -mmin +1 -exec rmdir {} \; 2>/dev/null || true
    return 0
  fi
  read -r cur cs _ 2>/dev/null < "$d/pid" || { cur=""; cs=""; }
  if [[ "$cur" == "$seen" ]] && { [[ -z "$cur" ]] || _hl_holder_stale "$cur" "$cs" "${DLAP_HARNESS_LOCK_MAX_AGE:-14400}"; }; then
    _hl_say "reclaiming stale harness lock (holder pid ${cur:-none} is gone or too old)"
    rm -rf "$d"
  fi
  rmdir "$g" 2>/dev/null || true
}

_hl_lock_flock() { # _hl_lock_flock <lock file> <label> <timeout secs>
  local file="$1" label="$2" timeout="$3" deadline left
  deadline=$((SECONDS + timeout))
  # Append mode: opening must not truncate the file.
  exec 9>>"$file" || _hl_die "cannot open the harness lock file $file"
  if ! flock -n 9; then
    _hl_say "waiting for the host-wide harness lock ($file); holder: $(_hl_holder_text "$file.holder")"
    while :; do
      left=$((deadline - SECONDS))
      (( left > 0 )) || _hl_die "gave up after ${timeout}s waiting for the harness lock $file; holder: $(_hl_holder_text "$file.holder")"
      (( left > 30 )) && left=30
      flock -w "$left" 9 && break
      _hl_say "still waiting for the harness lock; holder: $(_hl_holder_text "$file.holder")"
    done
  fi
  _HL_LOCK_BACKEND="flock"; _HL_LOCK_PATH="$file"
  printf '%s %s %s %s\n' "$$" "$(date +%s)" "$label" "$PWD" > "$file.holder"
}

_hl_lock_mkdir() { # _hl_lock_mkdir <lock dir> <label> <timeout secs>
  local d="$1" label="$2" timeout="$3" deadline next_msg hp hs
  local max_age="${DLAP_HARNESS_LOCK_MAX_AGE:-14400}" nopid_since=-1
  deadline=$((SECONDS + timeout)); next_msg=$SECONDS
  while :; do
    if mkdir "$d" 2>/dev/null; then
      printf '%s %s %s %s\n' "$$" "$(date +%s)" "$label" "$PWD" > "$d/pid"
      _HL_LOCK_BACKEND="mkdir"; _HL_LOCK_PATH="$d"
      return 0
    fi
    hp=""; hs=""
    read -r hp hs _ 2>/dev/null < "$d/pid" || true
    if [[ -z "$hp" ]]; then
      # The holder is between mkdir and writing its PID, or died in that gap: allow 10s.
      (( nopid_since < 0 )) && nopid_since=$SECONDS
      if (( SECONDS - nopid_since > 10 )); then _hl_reclaim "$d" ""; nopid_since=-1; fi
    else
      nopid_since=-1
      if _hl_holder_stale "$hp" "$hs" "$max_age"; then _hl_reclaim "$d" "$hp"; fi
    fi
    if (( SECONDS >= deadline )); then
      _hl_die "gave up after ${timeout}s waiting for the harness lock $d; holder: $(_hl_holder_text "$d/pid")"
    fi
    if (( SECONDS >= next_msg )); then
      _hl_say "waiting for the host-wide harness lock ($d); holder: $(_hl_holder_text "$d/pid")"
      next_msg=$((SECONDS + 30))
    fi
    sleep 1
  done
}

# The directory that holds the lock, one absolute path for every caller on this host (Q-577). It was
# ${TMPDIR:-/tmp}, which is /tmp in one Git Bash and /c/Users/<user>/AppData/Local/Temp in another, so
# two sessions did not exclude each other. The home directory is the one location every shell, every
# agent and CI agree on for a user: under Git Bash it comes from the Windows USERPROFILE (which does not
# vary with HOME or TMPDIR), elsewhere from the passwd entry, with $HOME as the last resort. The path is
# <home>/.dlap/harness-lock, works in Git Bash, Linux and CI, and is private to the user (mode 0700).
# DLAP_HARNESS_LOCK_DIR replaces it whole; it exists for the self-test.
harness_lock_base() {
  local home="${DLAP_HARNESS_LOCK_HOME:-}"
  if [[ -n "${DLAP_HARNESS_LOCK_DIR:-}" ]]; then printf '%s' "$DLAP_HARNESS_LOCK_DIR"; return 0; fi
  if [[ -z "$home" && -n "${USERPROFILE:-}" ]] && command -v cygpath >/dev/null 2>&1; then
    home="$(cygpath -u "$USERPROFILE" 2>/dev/null)"
  fi
  if [[ -z "$home" ]] && command -v getent >/dev/null 2>&1; then
    home="$(getent passwd "$(id -u)" 2>/dev/null | cut -d: -f6)"
  fi
  [[ -n "$home" ]] || home="${HOME:-}"
  [[ "$home" == /* ]] || { echo "harness_lock_base: no absolute home directory (got '$home')" >&2; return 1; }
  printf '%s' "${home%/}/.dlap/harness-lock"
}

# Take the host-wide harness lock for the life of this shell. Idempotent, and a no-op in a
# child of a live lock holder (DLAP_HARNESS_LOCK_HELD).
harness_lock_acquire() { # harness_lock_acquire <label>
  local label="${1:-harness}" base backend timeout
  if [[ -n "${DLAP_HARNESS_LOCK_HELD:-}" ]] && _hl_pid_alive "$DLAP_HARNESS_LOCK_HELD"; then
    _hl_say "harness lock already held by ancestor pid $DLAP_HARNESS_LOCK_HELD"
    return 0
  fi
  [[ -z "$_HL_LOCK_BACKEND" ]] || return 0
  base="$(harness_lock_base)" || _hl_die "cannot work out the harness lock directory"
  timeout="${DLAP_HARNESS_LOCK_TIMEOUT:-3600}"
  [[ "$timeout" =~ ^[0-9]+$ ]] || _hl_die "DLAP_HARNESS_LOCK_TIMEOUT must be a number of seconds (got '$timeout')"
  mkdir -p "$base" 2>/dev/null || _hl_die "cannot create the harness lock directory $base"
  chmod 700 "$base" 2>/dev/null || true
  backend="${DLAP_HARNESS_LOCK_BACKEND:-}"
  if [[ -z "$backend" ]]; then
    if command -v flock >/dev/null 2>&1; then backend="flock"; else backend="mkdir"; fi
  fi
  case "$backend" in
    flock) command -v flock >/dev/null 2>&1 || _hl_die "DLAP_HARNESS_LOCK_BACKEND=flock but flock is not installed"
           _hl_lock_flock "$base/dlap-e2e-harness.lock" "$label" "$timeout" ;;
    mkdir) _hl_lock_mkdir "$base/dlap-e2e-harness.lock.d" "$label" "$timeout" ;;
    *)     _hl_die "unknown DLAP_HARNESS_LOCK_BACKEND '$backend' (want flock or mkdir)" ;;
  esac
  export DLAP_HARNESS_LOCK_HELD=$$
  trap harness_lock_release EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  _hl_say "host-wide harness lock acquired (backend=$backend, label=$label, pid=$$)"
}

# Release the lock if this shell holds it. Safe to call twice, and from an EXIT trap.
harness_lock_release() {
  local hp=""
  case "$_HL_LOCK_BACKEND" in
    flock) rm -f "$_HL_LOCK_PATH.holder" 2>/dev/null || true
           exec 9>&- ;;
    mkdir) read -r hp _ 2>/dev/null < "$_HL_LOCK_PATH/pid" || hp=""
           [[ "$hp" == "$$" ]] && rm -rf "$_HL_LOCK_PATH" ;;
  esac
  # The marker is only valid while we hold the lock: a later acquire must really lock.
  [[ "${DLAP_HARNESS_LOCK_HELD:-}" == "$$" ]] && unset DLAP_HARNESS_LOCK_HELD
  _HL_LOCK_BACKEND=""; _HL_LOCK_PATH=""
  return 0
}

# ---------------------------------------------------------------------------
# Compose project name
# ---------------------------------------------------------------------------

# Export COMPOSE_PROJECT_NAME for the worktree rooted at <root>, unless the caller set one.
# Shape: dlap-e2e-<directory slug>-<8 hex digits of cksum(lowercased path)>. Valid compose
# project names are lowercase letters, digits, dash and underscore.
harness_project_name() { # harness_project_name <worktree root>
  [[ -n "${COMPOSE_PROJECT_NAME:-}" ]] && return 0
  local root="$1" slug sum
  command -v cygpath >/dev/null 2>&1 && root="$(cygpath -m "$root")"
  root="$(printf '%s' "$root" | tr '[:upper:]' '[:lower:]')"
  slug="$(basename "$root" | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//' | cut -c1-24 | sed -E 's/-+$//')"
  sum="$(printf '%s' "$root" | cksum | cut -d' ' -f1)"
  COMPOSE_PROJECT_NAME="$(printf 'dlap-e2e-%s-%08x' "${slug:-wt}" "$sum")"
  export COMPOSE_PROJECT_NAME
}

# ---------------------------------------------------------------------------
# Host ports
# ---------------------------------------------------------------------------

# SERVICE=host port. These equal the defaults written into the dev compose pair
# (${DLAP_PORT_<SERVICE>:-<port>}); e2e-tests/test-harness-lib.sh fails when they drift.
HARNESS_PORT_DEFAULTS=(
  POSTGRES=5432 KEYCLOAK=8180 KEYCLOAK_HTTPS=8443 KAFKA=9092 KAFKA_CONTROLLER=9093
  PROMETHEUS=9090 GRAFANA=3001 LOCALSTACK=4566 UNITYCATALOG=18080
  NEW_UI=3000 GATEWAY=8081 DISCOVERY=8091 CATALOG=8082 POLICYENGINE=8083 REPORTING=8084
  OLLAMA=11434 AUTOLABEL=8085 AUTOARCHIVAL=8086 AUTODELETE=8087 LOGINGEST=8090
  AUTOCOMPLIANCE=8088 ADMIN=8089
)

# Export DLAP_PORT_<SERVICE> for every service, keeping caller overrides. Exporting the
# defaults too means the scripts' curl URLs and the compose file always agree, even when a
# .env file in the compose directory sets a different value (the environment wins there).
harness_port_defaults() {
  local kv name value var
  for kv in "${HARNESS_PORT_DEFAULTS[@]}"; do
    name="${kv%%=*}"; value="${kv#*=}"; var="DLAP_PORT_$name"
    value="${!var:-$value}"
    if ! [[ "$value" =~ ^[0-9]+$ ]] || (( 10#$value < 1 || 10#$value > 65535 )); then
      _hl_die "$var must be a port number from 1 to 65535 (got '$value')"
    fi
    export "$var=$value"
  done
}

# ---------------------------------------------------------------------------
# JDK for the Gradle jar build
# ---------------------------------------------------------------------------

# DLAP_JDK21 wins. Otherwise the portable Windows JDK is used when it exists (the Windows
# dev box cannot run Gradle 8.7 on its default JDK 24). On Linux and in CI the JAVA_HOME the
# environment already provides (actions/setup-java) is kept untouched.
harness_select_jdk() {
  local jdk21_win="${USERPROFILE:+$(cygpath -u "$USERPROFILE" 2>/dev/null)}/jdk21-temp/jdk-21.0.11+10"
  if [[ -n "${DLAP_JDK21:-}" ]]; then
    export JAVA_HOME="$DLAP_JDK21"
  elif [[ -d "$jdk21_win" ]]; then
    export JAVA_HOME="$jdk21_win"
  fi
}

# One call for the common prelude: lock first, then everything that reads shared state.
harness_init() { # harness_init <label> <repo root>
  harness_lock_acquire "$1"
  harness_project_name "$2"
  harness_port_defaults
  harness_select_jdk
  _hl_say "compose project: $COMPOSE_PROJECT_NAME ; host ports: DLAP_PORT_* (defaults unless overridden)"
}

# ---------------------------------------------------------------------------
# Waiting for a container
# ---------------------------------------------------------------------------

# True when <container>'s log holds the fixed string <pattern>. grep -c reads the whole log, so
# the writer is never cut off. `docker logs | grep -q` under pipefail fails as soon as grep exits
# on its first match (rc 141 from SIGPIPE on Linux, rc 1 from docker.exe on Windows), which made
# every wait run to its full timeout.
harness_log_has() { # harness_log_has <container> <fixed string>
  docker logs "$1" 2>&1 | grep -F -c -- "$2" >/dev/null
}

# Poll the log every <step> seconds, at most <tries> times. On success sets HARNESS_WAITED to the
# seconds spent and returns 0; returns 1 on timeout.
# shellcheck disable=SC2034  # HARNESS_WAITED is read by the calling script
harness_wait_log() { # harness_wait_log <container> <fixed string> <tries> <step secs>
  local c="$1" pat="$2" tries="$3" step="$4" i start=$SECONDS
  HARNESS_WAITED=0
  for ((i = 1; i <= tries; i++)); do
    if harness_log_has "$c" "$pat"; then HARNESS_WAITED=$((SECONDS - start)); return 0; fi
    sleep "$step"
  done
  return 1
}

# ---------------------------------------------------------------------------
# Clean slate
# ---------------------------------------------------------------------------

# The compose files pin container_name, so a stack another project left up blocks `compose up`
# with "container name already in use". For every container_name the COMPOSE array declares:
#   - owned by this run's project: left to the `down` in harness_reset_stack;
#   - owned by another harness project (dlap-e2e-*): that project is taken down with its volumes;
#   - anything else (the dev stack, a hand-made container): only the colliding container is
#     removed. Its volumes and network are kept.
harness_evict_colliding() {
  local names n line proj p seen=" "
  local harness_projects=()
  names="$("${COMPOSE[@]}" config 2>/dev/null | sed -n -E 's/^[[:space:]]+container_name:[[:space:]]*//p' | tr -d '\r"')"
  for n in $names; do
    line="$(MSYS_NO_PATHCONV=1 docker ps -a --filter "name=^${n}\$" \
        --format '{{.Names}}|{{.Label "com.docker.compose.project"}}' 2>/dev/null | tr -d '\r')"
    [[ -n "$line" ]] || continue
    proj="${line#*|}"
    [[ "$proj" == "${COMPOSE_PROJECT_NAME:-}" ]] && continue
    if [[ "$proj" == dlap-e2e-* ]]; then
      [[ "$seen" == *" $proj "* ]] || { seen="$seen$proj "; harness_projects+=("$proj"); }
    else
      _hl_say "evicting container $n (compose project '${proj:-none}'); its volumes are kept"
      docker rm -f "$n" >/dev/null 2>&1 || _hl_warn "could not remove container $n"
    fi
  done
  for p in "${harness_projects[@]+"${harness_projects[@]}"}"; do
    _hl_say "evicting leftover harness project '$p' (down -v)"
    docker compose -p "$p" down -v --remove-orphans >/dev/null 2>&1 || _hl_warn "could not take down project $p"
  done
}

# Clean slate for this run: evict colliding foreign containers, then wipe this project.
harness_reset_stack() {
  harness_evict_colliding
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
}
