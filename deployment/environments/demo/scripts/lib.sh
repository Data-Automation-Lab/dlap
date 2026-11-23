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
# Shared helpers of the hosted-demo scripts (DEMO-01). SOURCE this file, do not run it:
#
#   source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
#   demo_init "$ENV_FILE"
#   demo_dc ps
#
# Settings are read in this order: the process environment, then the environment file
# (.env.demo), then the default in the script. The file is read, never sourced, so nothing in it is
# executed.
#
# Windows (Git Bash): paths handed to docker.exe go through cygpath, and MSYS_NO_PATHCONV=1 keeps
# in-container paths such as /busybox from being rewritten.

DEMO_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEMO_DIR="$(cd "$DEMO_LIB_DIR/.." && pwd)"
DEMO_REPO_ROOT="$(cd "$DEMO_DIR/../../.." && pwd)"
DEMO_DEV_DIR="$DEMO_REPO_ROOT/deployment/environments/dev"
export MSYS_NO_PATHCONV=1

demo_say()  { echo "[demo] $*"; }
demo_warn() { echo "[demo] WARN: $*" >&2; }
demo_die()  { echo "[demo] FAIL: $*" >&2; exit 1; }

# A path as the docker CLI wants it: a drive path on Windows, unchanged elsewhere.
demo_native_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# --- Settings -----------------------------------------------------------------------------------

DEMO_ENV_FILE="${DEMO_ENV_FILE:-$DEMO_DIR/.env.demo}"

# Last assignment of KEY in the environment file, without evaluating anything. Surrounding quotes
# and a trailing carriage return are removed. Prints nothing when the file or the key is missing.
demo_env_value() { # demo_env_value <KEY>
  local key="$1" line value
  [ -f "$DEMO_ENV_FILE" ] || return 0
  line="$(grep -E "^[[:space:]]*${key}=" "$DEMO_ENV_FILE" | tail -n 1 || true)"
  [ -n "$line" ] || return 0
  value="${line#*=}"
  value="${value%$'\r'}"
  case "$value" in
    \"*\") value="${value#\"}"; value="${value%\"}" ;;
    \'*\') value="${value#\'}"; value="${value%\'}" ;;
  esac
  printf '%s' "$value"
}

# Setting KEY: process environment, then file, then DEFAULT.
demo_cfg() { # demo_cfg <KEY> [DEFAULT]
  local key="$1" def="${2-}" fromfile
  if [ -n "${!key-}" ]; then printf '%s' "${!key}"; return 0; fi
  fromfile="$(demo_env_value "$key")"
  if [ -n "$fromfile" ]; then printf '%s' "$fromfile"; else printf '%s' "$def"; fi
}

# --- Compose ------------------------------------------------------------------------------------

# One compose file per line: the quickstart pair, this overlay, the Keycloak overlay of DEMO-02 when
# it exists, and whatever DEMO_EXTRA_COMPOSE names (space separated; measure.sh uses it).
# DEMO_OVERLAY_FILE replaces the overlay itself; test-demo.sh uses it to check mutated copies.
demo_compose_files() {
  printf '%s\n' "$DEMO_DEV_DIR/docker-compose.yml" "$DEMO_DEV_DIR/docker-compose.microservices.yml" \
    "${DEMO_OVERLAY_FILE:-$DEMO_DIR/docker-compose.demo.yml}"
  if [ -f "$DEMO_DIR/keycloak/docker-compose.demo.keycloak.yml" ]; then
    printf '%s\n' "$DEMO_DIR/keycloak/docker-compose.demo.keycloak.yml"
  fi
  local extra
  for extra in ${DEMO_EXTRA_COMPOSE:-}; do printf '%s\n' "$extra"; done
}

# The docker compose arguments up to (not including) the subcommand, one per line.
demo_compose_args() {
  local f
  printf '%s\n' --project-name "$(demo_cfg COMPOSE_PROJECT_NAME dlap-demo)"
  if [ -f "$DEMO_ENV_FILE" ]; then
    printf '%s\n' --env-file "$(demo_native_path "$DEMO_ENV_FILE")"
  fi
  while IFS= read -r f; do
    printf '%s\n' -f "$(demo_native_path "$f")"
  done < <(demo_compose_files)
}

# Fill DEMO_ARGS and export what compose reads from the process environment rather than from
# --env-file (COMPOSE_PROFILES).
demo_init() { # demo_init [env file]
  [ -z "${1-}" ] || DEMO_ENV_FILE="$1"
  DEMO_ARGS=()
  local arg
  while IFS= read -r arg; do DEMO_ARGS+=("$arg"); done < <(demo_compose_args)
  if [ -z "${COMPOSE_PROFILES:-}" ] && [ -n "$(demo_env_value COMPOSE_PROFILES)" ]; then
    export COMPOSE_PROFILES
    COMPOSE_PROFILES="$(demo_env_value COMPOSE_PROFILES)"
  fi
}

# docker compose with the demo files. Warnings about the obsolete `version` key of the quickstart
# files are noise here and are dropped.
demo_dc() {
  docker compose "${DEMO_ARGS[@]}" "$@" 2> >(grep -v 'attribute `version` is obsolete' >&2)
}

demo_project() { demo_cfg COMPOSE_PROJECT_NAME dlap-demo; }

# Services of the merged model that are enabled by the active profiles, one per line.
demo_services() { demo_dc config --services | tr -d '\r'; }

# The JVM services of the active model, in start order (wave 1 first).
demo_jvm_services() {
  local all svc
  all="$(demo_services)"
  for svc in da-catalog da-policyengine da-discovery da-logingest da-reporting da-autolabel \
             da-autoarchival da-autodelete da-autocompliance da-admin-service; do
    if grep -qx "$svc" <<<"$all"; then printf '%s\n' "$svc"; fi
  done
}

# --- State folder -------------------------------------------------------------------------------

# Absolute path of the state folder (golden dump, flags, measurements, last reset). A relative
# DEMO_STATE_DIR starts at deployment/environments/dev, like every path in the compose files.
demo_state_dir() {
  local d
  d="$(demo_cfg DEMO_STATE_DIR "")"
  if [ -z "$d" ]; then d="$DEMO_DIR/state"; fi
  case "$d" in
    /*|[A-Za-z]:*) ;;
    *) d="$DEMO_DEV_DIR/$d" ;;
  esac
  mkdir -p "$d" 2>/dev/null || true
  (cd "$d" 2>/dev/null && pwd) || printf '%s' "$d"
}

# --- Database -----------------------------------------------------------------------------------

# Run SQL in a database as the superuser; prints the bare result with no blanks around it.
demo_pq() { # demo_pq <database> <sql>
  demo_dc exec -T postgresql psql -U dlap -d "$1" -tAc "$2" 2>/dev/null | tr -d '\r' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

# The databases that hold the demo state: the shared schema plus the per-service databases of the
# services in the Lite profile.
DEMO_DUMP_DATABASES_DEFAULT="dlap_common da_discovery da_policyengine da_reporting da_autolabel da_autoarchival da_autodelete da_logingest"

# --- Waiting ------------------------------------------------------------------------------------

# Wait until a URL answers with one of the accepted status codes (default 200).
demo_wait_http() { # demo_wait_http <url> <timeout secs> [codes, space separated]
  local url="$1" timeout="$2" codes="${3:-200}" start=$SECONDS code
  while (( SECONDS - start < timeout )); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$url" 2>/dev/null || true)"
    if [[ " $codes " == *" $code "* ]]; then return 0; fi
    sleep 3
  done
  return 1
}

# The health status of a service's container: healthy, unhealthy, starting, or none.
demo_health() { # demo_health <service>
  local id
  id="$(demo_dc ps -q "$1" 2>/dev/null | tr -d '\r' | head -n 1)"
  [ -n "$id" ] || { echo "absent"; return 0; }
  docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null | tr -d '\r'
}

# Wait until every named service is healthy.
demo_wait_healthy() { # demo_wait_healthy <timeout secs> <service>...
  local timeout="$1" start=$SECONDS svc pending
  shift
  while (( SECONDS - start < timeout )); do
    pending=""
    for svc in "$@"; do
      [ "$(demo_health "$svc")" = "healthy" ] || pending="$pending $svc"
    done
    [ -z "$pending" ] && return 0
    sleep 5
  done
  demo_warn "not healthy after ${timeout}s:$pending"
  return 1
}

# --- Locking ------------------------------------------------------------------------------------

# One reset or measurement at a time per state folder. flock where it exists, a mkdir lock with a
# PID check elsewhere. The lock dies with the script. In the mkdir lock the pid file, created with
# noclobber (O_EXCL), decides the owner: mkdir alone is not exclusive with uutils coreutils 0.10.0,
# the default on Ubuntu 26.04, which lets several concurrent callers succeed.
_demo_claim_dir() { mkdir "$1" 2>/dev/null && (set -o noclobber; echo "$$" > "$1/pid") 2>/dev/null; }
demo_lock() { # demo_lock <name>
  local dir lockfile
  dir="$(demo_state_dir)"
  if command -v flock >/dev/null 2>&1; then
    lockfile="$dir/.lock-$1"
    exec 8>>"$lockfile" || demo_die "cannot open $lockfile"
    flock -n 8 || demo_die "another '$1' is running (lock $lockfile)"
  else
    lockfile="$dir/.lock-$1.d"
    if ! _demo_claim_dir "$lockfile"; then
      local pid
      pid="$(cat "$lockfile/pid" 2>/dev/null || true)"
      if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        demo_die "another '$1' is running (pid $pid)"
      fi
      # Take over under a guard, re-checked there, so two callers that both saw a stale lock cannot
      # both remove and take it. A lock with no pid yet may be mid-claim: stale after a minute.
      if ! _demo_claim_dir "$lockfile.reclaim"; then
        find "$lockfile.reclaim" -maxdepth 0 -mmin +1 -exec rm -rf {} \; 2>/dev/null || true
        demo_die "cannot take lock $lockfile"
      fi
      if [ -d "$lockfile" ] && [ "$(cat "$lockfile/pid" 2>/dev/null || true)" = "$pid" ] \
        && { [ -n "$pid" ] || [ -n "$(find "$lockfile" -maxdepth 0 -mmin +1 2>/dev/null)" ]; }; then
        rm -rf "$lockfile"
      fi
      _demo_claim_dir "$lockfile" || { rm -rf "$lockfile.reclaim"; demo_die "cannot take lock $lockfile"; }
      rm -rf "$lockfile.reclaim"
    fi
    # shellcheck disable=SC2064
    trap "rm -rf '$lockfile'" EXIT
  fi
}

# --- Development hosts ----------------------------------------------------------------------------

# The quickstart files pin container names, so a second project on the same Docker host collides.
# On a development machine that also runs the e2e harnesses, set DEMO_EVICT_COLLIDING=1: this takes
# the host-wide harness lock (e2e-tests/harness-lib.sh) and removes colliding containers of other
# projects, exactly as the harnesses do. Not for the demo VM.
demo_harness_prepare() {
  [ "${DEMO_EVICT_COLLIDING:-0}" = "1" ] || return 0
  local lib="$DEMO_REPO_ROOT/e2e-tests/harness-lib.sh"
  [ -f "$lib" ] || demo_die "DEMO_EVICT_COLLIDING=1 needs $lib"
  # shellcheck source=/dev/null
  source "$lib"
  harness_lock_acquire demo
  # harness_evict_colliding reads the COMPOSE array of the caller.
  COMPOSE=(docker compose "${DEMO_ARGS[@]}")
  COMPOSE_PROJECT_NAME="$(demo_project)"
  export COMPOSE_PROJECT_NAME
  harness_evict_colliding
}
